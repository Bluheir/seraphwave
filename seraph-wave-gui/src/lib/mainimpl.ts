import type {
	AudioEvent,
	CreateCode,
	FullApi,
	GetMetaInfo,
	InitialConnState,
	MetaInfo,
	ProtocolError,
	SessionCodeReceive,
	VoiceClient,
	VoiceClientMgr,
} from "./api"

export class MainImpl implements CreateCode, GetMetaInfo, FullApi, VoiceClientMgr {
	async createCode(): Promise<string> {
		const request = new Request("/code", {
			method: "POST",
		})

		return (await (await fetch(request)).json()).code
	}
	async getMeta(): Promise<MetaInfo & any> {
		return await (await fetch("/meta")).json()
	}
	createClient(info: MetaInfo & any, initialState: InitialConnState): Promise<VoiceClient> {
		const ws = new WebSocket(info.webSocketUrl)

        async function voiceClient() {
            const vc = new MainVoiceClient(ws, initialState)
            await vc.start()
            return vc
        }
		
        return new Promise<VoiceClient>((resolve, reject) => {
            ws.onopen = () => {
                resolve(voiceClient())
            }
            ws.onerror = e => {
                reject("cannot connect")
            }
        })
	}
}

type ConnectionState =
	| InitialConnState
	| {
			type: "sessionCodeAwait"
	  }
	| {
			type: "offline"
	  }
	| {
			type: "awaitFirstUpdate"
	  }
	| {
			type: "online"
	  }
type PlayerUpdate = {
	updateType: "playerStatus"
	value: "online" | "offline"
}

function parseAudioArr(buffer: ArrayBuffer): AudioEvent {
	const view = new DataView(buffer)

	const type = view.getInt8(0)

	// audio packet
	if (type === 0) {
		return {
			type: "audioPacket",
			uuid: (view.getBigUint64(1) << BigInt(64)) | view.getBigUint64(9),
			pos: {
				x: view.getFloat64(17),
				y: view.getFloat64(25),
				z: view.getFloat64(33),
			},
			dir: {
				x: view.getFloat64(41),
				y: view.getFloat64(49),
				z: view.getFloat64(57),
			},
			buf: view.buffer.slice(65, view.buffer.byteLength),
		}
	}
	// rotation update
	else {
		return {
			type: "rotationUpdate",
			rotation: {
				x: view.getFloat64(1),
				y: view.getFloat64(9),
				z: view.getFloat64(17),
			},
		}
	}
}

export class MainVoiceClient implements VoiceClient {
	private client: WebSocket
	private connState: ConnectionState

	// events
	private onUserDataI:
		| ((data: SessionCodeReceive) => Promise<void>)
		| ((data: SessionCodeReceive) => void)
	private onCloseI: (() => Promise<void>) | (() => void)
	private onAudioI: ((packet: AudioEvent) => Promise<void>) | ((packet: AudioEvent) => void)
	private onConnectI: (() => void) | (() => Promise<void>)
	private onErrorI: ((err: ProtocolError) => void) | ((err: ProtocolError) => Promise<void>)

	onSessionCode(
		ev: ((data: SessionCodeReceive) => Promise<void>) | ((data: SessionCodeReceive) => void),
	) {
		this.onUserDataI = ev
	}
	onClose(ev: (() => void) | (() => Promise<void>)): void {
		this.onCloseI = ev
	}
	onAudio(ev: ((packet: AudioEvent) => Promise<void>) | ((packet: AudioEvent) => void)): void {
		this.onAudioI = ev
	}
	onConnect(ev: (() => void) | (() => Promise<void>)): void {
		this.onConnectI = ev
	}
	onError(ev: ((err: ProtocolError) => void) | ((err: ProtocolError) => Promise<void>)): void {
		this.onErrorI = ev
	}

	constructor(client: WebSocket, connState: InitialConnState) {
		this.client = client
		this.client.binaryType = "arraybuffer"
		this.connState = connState

		this.onUserDataI = () => {}
		this.onCloseI = () => {}
		this.onAudioI = () => {}
		this.onConnectI = () => {}
		this.onErrorI = () => {}

		client.onmessage = async (msg) => {
			switch (this.connState.type) {
				case "sessionCodeAwait":
					const sessionInfo = JSON.parse(msg.data)
					if (sessionInfo.errorCode) {
						await this.onErrorI(sessionInfo)
					} else {
						await this.onUserDataI(sessionInfo)
						this.connState = { type: "awaitFirstUpdate" }
					}
					break
				case "awaitFirstUpdate":
					const firstUpdate = JSON.parse(msg.data)
					if (firstUpdate.errorCode) {
						await this.onErrorI(firstUpdate)
					} else {
						this.connState = { type: firstUpdate.value }
						await this.onConnectI()
					}
					break
				case "offline":
					const update = JSON.parse(msg.data)
					if (update.errorCode) {
						await this.onErrorI(update)
					} else {
						this.connState = { type: update.value }
					}
					break

				case "online":
					if (typeof msg.data === "string") {
						const update = JSON.parse(msg.data)
						if (update.errorCode) {
							await this.onErrorI(update)
						} else {
							this.connState = { type: update.value }
						}
					} else {
						const data: ArrayBuffer = msg.data
						await this.onAudioI(parseAudioArr(data))
					}
					break
			}
		}
		client.onclose = async (_) => await this.onCloseI()
	}
	async start() {
		switch (this.connState.type) {
			case "full":
				this.client.send(
					JSON.stringify({
						protocolV: 0,
						type: "full",
						code: this.connState.code,
						uuid: this.connState.uuid,
					}),
				)
				this.connState = { type: "awaitFirstUpdate" }
				break

			case "temp":
				this.client.send(
					JSON.stringify({
						protocolV: 0,
						...this.connState,
					}),
				)
				this.connState = { type: "sessionCodeAwait" }
				break
		}
	}

	async send(blob: Blob | ArrayBufferLike) {
		if (this.connState.type == "online") {
			this.client.send(blob)
		}
	}
}
