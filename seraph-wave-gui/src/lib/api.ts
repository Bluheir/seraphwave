import { Buffer } from "buffer"
import { DummyImpl } from "./dummy"
import { MainImpl } from "./mainimpl"

import OpusScript from "opusscript"
import { writable, type Writable } from "svelte/store"

export interface CreateCode {
	/**
	 * Generates a code to be used in the Minecraft command.
	 */
	createCode(): Promise<string>
}

export type MetaInfo = {
	/**
	 * The welcome message.
	 */
	welcomeMsg: string
	/**
	 * If alt accounts are supported.
	 */
	altAccounts: boolean
}
export type InitialConnState =
	| {
			type: "temp"
			code: string
	  }
	| {
			type: "full"
			uuid: string
			code: string
	  }

export type SessionCodeReceive = {
	code: string
	uuid: string
	username: string
}

export type Vec3d = {
	x: number
	y: number
	z: number
}

export type AudioPacket = {
	type: "audioPacket"
	uuid: bigint
	pos: Vec3d
	dir: Vec3d
	buf: ArrayBuffer
}

export type AudioEvent =
	| AudioPacket
	| {
			type: "rotationUpdate"
			rotation: Vec3d
	  }
export type AudioInfoParams = {
	sampleRate?: number
	channelCount?: number
	msPerFrame?: number
	bitDepth?: number
}

export type ProtocolError =
	| {
			/**
			 * The temporary code does not exist.
			 */
			errorCode: 0
			msg: string
	  }
	| {
			/**
			 * The code was already consumed by another client.
			 */
			errorCode: 1
			msg: string
	  }
	| {
			/**
			 * Incorrect session key.
			 */
			errorCode: 2
			msg: string
	  }

export class AudioInfo {
	readonly sampleRate: number
	readonly channelCount: number
	readonly msPerBuf: number
	readonly bitDepth: number

	readonly frameSize: number
	readonly packetSize: number

	constructor(params: AudioInfoParams) {
		this.sampleRate = params.sampleRate || 48000
		this.channelCount = params.channelCount || 2
		this.bitDepth = params.bitDepth || 16
		this.msPerBuf = params.msPerFrame || 60

		this.frameSize = (this.sampleRate * this.msPerBuf) / 1000
		this.packetSize = (this.frameSize * this.channelCount * this.bitDepth) / 8
	}
}

const AUDIO_PARAMS = new AudioInfo({
	sampleRate: 48000,
	bitDepth: 16,
	channelCount: 2,
	msPerFrame: 60,
})

export interface GetMetaInfo {
	getMeta(): Promise<MetaInfo | any>
}

export interface VoiceClient {
	onSessionCode(
		ev: ((data: SessionCodeReceive) => Promise<void>) | ((data: SessionCodeReceive) => void),
	): void
	onConnect(ev: (() => void) | (() => Promise<void>)): void
	onClose(ev: (() => void) | (() => Promise<void>)): void
	onError(ev: ((err: ProtocolError) => void) | ((err: ProtocolError) => Promise<void>)): void
	onAudio(ev: ((packet: AudioEvent) => void) | ((packet: AudioEvent) => Promise<void>)): void
	send(blob: Blob | ArrayBufferLike): Promise<void>
}
export interface VoiceClientMgr {
	createClient(info: MetaInfo | any, initialState: InitialConnState): Promise<VoiceClient>
}

export interface FullApi extends CreateCode, GetMetaInfo, VoiceClientMgr {}
export const apiImpl: FullApi = new MainImpl()

export let clientInstance: Writable<VoiceClient | undefined> = writable(undefined)

export class AudioManager {
	private audioCtx: AudioContext
	private codec: OpusScript
	private jitters: Map<bigint, JitterBuffer>

	private constructor(audioCtx: AudioContext, codec: OpusScript) {
		this.audioCtx = audioCtx
		this.jitters = new Map()
		this.codec = codec
	}

	static async createAudioMgr(
		f: (s: MediaStream) => MediaRecorder,
		client: VoiceClient,
		audioCtx: AudioContext,
	): Promise<AudioManager> {
		const codec = new OpusScript(AUDIO_PARAMS.sampleRate as any, AUDIO_PARAMS.channelCount, 2048, {
			wasm: false,
		})
		const mgr = new AudioManager(audioCtx, codec)

		client.onAudio(mgr.onAudio.bind(mgr))

		await mgr.startAudio(f, client)

		return mgr
	}

	private async startAudio(f: (s: MediaStream) => MediaRecorder, client: VoiceClient) {
		const stream = await navigator.mediaDevices.getUserMedia({
			audio: {
				sampleRate: AUDIO_PARAMS.sampleRate,
				channelCount: AUDIO_PARAMS.channelCount,
				sampleSize: AUDIO_PARAMS.bitDepth,
				echoCancellation: true,
			},
			video: false,
		})

		const mediaRecorder = f(stream)

		mediaRecorder.ondataavailable = async (event) => {
			let buffer = await event.data.arrayBuffer()

			if (buffer.byteLength === AUDIO_PARAMS.packetSize) {
			} else if (buffer.byteLength === AUDIO_PARAMS.packetSize / 2) {
				// convert 1 channel to 2 channel
				const dataView = new DataView(buffer)
				const newBuffer = new Uint16Array(buffer.byteLength)

				for (let i = 0; i < buffer.byteLength / 2; i++) {
					const value = dataView.getUint16(i, true)
					newBuffer[i] = value
					newBuffer[i + 1] = value
				}

				buffer = newBuffer.buffer
			} else {
				// skip the first packet that contains the 44 byte WAV header
				return
			}

			//const encoded = this.codec.encode(Buffer.from(buffer), frameSize).buffer
			const encoded = buffer
			await client.send(encoded)
		}

		mediaRecorder.start(AUDIO_PARAMS.msPerBuf)
	}
	private async onAudio(packet: AudioEvent) {
		switch (packet.type) {
			case "audioPacket":
				this.playAudioPacket(packet)
				break
			case "rotationUpdate":
				const { x, y, z } = packet.rotation
				const listener = this.audioCtx.listener
				listener.setOrientation(x, y, z, 0, 1, 0)
				break
		}
	}

	private async playAudioPacket(packet: AudioPacket) {
		let jitter = this.jitters.get(packet.uuid)

		if (!jitter) {
			jitter = new JitterBuffer(this.audioCtx, this.codec)
			this.jitters.set(packet.uuid, jitter)
		}

		jitter.pushPacket(packet)
	}
}
class JitterBuffer {
	private audioCtx: AudioContext
	private codec: OpusScript
	private panner: PannerNode
	private latency: number
	private startingTime: number
	private packetsPlayed: number
	private lastPlayed: number

	constructor(audioCtx: AudioContext, codec: OpusScript) {
		this.latency = 4
		this.audioCtx = audioCtx
		this.codec = codec
		this.startingTime = 0
		this.packetsPlayed = 0
		this.lastPlayed = 0

		this.panner = new PannerNode(audioCtx, {
			panningModel: "HRTF",

			positionX: 0,
			positionY: 0,
			positionZ: 0,

			orientationX: 1,
			orientationY: 0,
			orientationZ: 0,
			distanceModel: "exponential",
			channelCount: 2,

			maxDistance: 10000,

			coneInnerAngle: 60,
			coneOuterAngle: 90,

			coneOuterGain: 0.6,

			rolloffFactor: 1.3,
			refDistance: 1,
		})

		this.panner.connect(this.audioCtx.destination)
	}

	pushPacket(packet: AudioPacket) {
		const currentTime = this.audioCtx.currentTime
		const diff = (currentTime - this.lastPlayed) * 1000
		if (this.packetsPlayed === 0 || diff > AUDIO_PARAMS.msPerBuf + 60) {
			this.startingTime = currentTime
			this.packetsPlayed = 0
		}

		let timeS =
			this.startingTime + ((this.latency + this.packetsPlayed) * AUDIO_PARAMS.msPerBuf) / 1000

		this.playAudioPacket(packet, timeS)
		this.packetsPlayed += 1
		this.lastPlayed = currentTime
	}
	private playAudioPacket(packet: AudioPacket, timeS: number) {
		const { pos, dir, buf } = packet

		this.panner.positionX.setValueAtTime(pos.x, timeS)
		this.panner.positionY.setValueAtTime(pos.y, timeS)
		this.panner.positionZ.setValueAtTime(pos.z, timeS)

		this.panner.orientationX.setValueAtTime(dir.x, timeS)
		this.panner.orientationY.setValueAtTime(dir.y, timeS)
		this.panner.orientationZ.setValueAtTime(dir.z, timeS)

		const decoded = buf
		//const decoded = this.codec.decode(Buffer.from(buf)).buffer
		const [left, right] = pcmToF32Channels(decoded)

		const audioBuf = new AudioBuffer({
			numberOfChannels: AUDIO_PARAMS.channelCount,
			length: AUDIO_PARAMS.frameSize,
			sampleRate: AUDIO_PARAMS.sampleRate,
		})
		audioBuf.copyToChannel(left, 0)
		audioBuf.copyToChannel(right, 1)

		const audio = this.audioCtx.createBufferSource()
		audio.buffer = audioBuf
		audio.connect(this.panner)
		audio.start(timeS)
	}
}

function pcmToF32Channels(arrayBuffer: ArrayBufferLike) {
	const dataView = new DataView(arrayBuffer)
	const sampleCount = dataView.byteLength / 2 // Each sample is 16 bits (2 bytes)

	const channel1 = new Float32Array(sampleCount / 2)
	const channel2 = new Float32Array(sampleCount / 2)

	for (let i = 0; i < sampleCount / 2; i++) {
		// Read 16-bit samples (PCM)
		const sample1 = dataView.getInt16(i * 4, true) // Channel 1
		const sample2 = dataView.getInt16(i * 4 + 2, true) // Channel 2

		// Convert to Float32 (-1.0 to 1.0)
		channel1[i] = sample1 / 32768 // 16-bit PCM normalization
		channel2[i] = sample2 / 32768 // 16-bit PCM normalization
	}

	return [channel1, channel2]
}
