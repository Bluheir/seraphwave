import { DummyImpl } from "./dummy"
import { MainImpl } from "./mainimpl"

import { writable, type Writable } from "svelte/store"
import encoderPath from "opus-recorder/dist/encoderWorker.min.js?url"
import Recorder from "opus-recorder"
import { OpusDecoderWebWorker } from "opus-decoder"

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

export type PosAudioPacket = {
	pos: Vec3d
	dir: Vec3d
	buf: AudioBuffer
}

export type AudioPacket = {
	type: "audioPacket"
	uuid: bigint
	pos: Vec3d
	dir: Vec3d
	buf: ArrayBufferLike
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
export let audioInstance: Writable<AudioManager | undefined> = writable(undefined)

export class AudioManager {
	private audioCtx: AudioContext
	private decoder: OpusDecoderWebWorker
	private jitters: Map<bigint, JitterBuffer>
	private mediaRecorder: any
	private inGainNode: GainNode | undefined
	private outGainNode: GainNode

	private constructor(audioCtx: AudioContext, decoder: OpusDecoderWebWorker) {
		this.audioCtx = audioCtx
		this.decoder = decoder
		this.jitters = new Map()
		this.mediaRecorder = undefined
		this.outGainNode = audioCtx.createGain()
		this.outGainNode.connect(this.audioCtx.destination)
	}

	static async createAudioMgr(audioCtx: AudioContext): Promise<AudioManager> {
		const decoder = new OpusDecoderWebWorker({
			channels: AUDIO_PARAMS.channelCount,
		})
		const mgr = new AudioManager(audioCtx, decoder)
		await decoder.ready

		await mgr.initAudio()

		return mgr
	}

	private async initAudio() {
		const baseStream = await navigator.mediaDevices.getUserMedia({
			audio: {
				sampleRate: AUDIO_PARAMS.sampleRate,
				sampleSize: AUDIO_PARAMS.bitDepth,
				echoCancellation: true,
			},
			video: false,
		})

		const source = new MediaStreamAudioSourceNode(this.audioCtx, { mediaStream: baseStream })
		this.inGainNode = this.audioCtx.createGain()

		const splitter = this.audioCtx.createChannelSplitter(source.channelCount)
		const merger = this.audioCtx.createChannelMerger(AUDIO_PARAMS.channelCount)

		source.connect(splitter)
		for(let i = 0; i < Math.max(AUDIO_PARAMS.channelCount, source.channelCount); i++) {
			splitter.connect(merger, i % source.channelCount, i % AUDIO_PARAMS.channelCount)
		}
		merger.connect(this.inGainNode)

		this.mediaRecorder = new Recorder({
			encoderPath,
			numberOfChannels: AUDIO_PARAMS.channelCount,
			encoderFrameSize: AUDIO_PARAMS.msPerBuf,
			encoderApplication: 2048, // VOIP Opus application
			maxFramesPerPage: 1,
			sourceNode: this.inGainNode,
			streamPages: true,
		}) as any
	}
	hookEvents(client: VoiceClient) {
		client.onAudio(this.onAudio.bind(this))

		let m = 0

		this.mediaRecorder.ondataavailable = async (oggPage: Uint8Array) => {
			// The first 2 pages contain only header data, ignore them.
			if (m < 2) {
				m += 1
				return
			}
			const rawOpus = extractOpusFrames(oggPage.buffer)

			await client.send(rawOpus.buffer)
		}
	}
	async startAudio() {
		if (this.mediaRecorder) {
			await this.mediaRecorder.start()
		}
	}
	async stopAudio() {
		if (this.mediaRecorder) {
			await this.mediaRecorder.stop()
		}
	}
	setInGain(gain: number) {
		if (this.inGainNode) {
			this.inGainNode.gain.value = gain
		}
	}
	setOutGain(gain: number) {
		this.outGainNode.gain.value = gain
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
			jitter = new JitterBuffer(this.audioCtx)
			jitter.connect(this.outGainNode)
			this.jitters.set(packet.uuid, jitter)
		}

		const decoded = await this.decoder.decodeFrame(new Uint8Array(packet.buf))

		const buf = new AudioBuffer({
			sampleRate: decoded.sampleRate,
			length: decoded.samplesDecoded,
			numberOfChannels: AUDIO_PARAMS.channelCount,
		})

		decoded.channelData.forEach((channelData, index) => {
			buf.copyToChannel(channelData, index)
		})

		jitter.pushPacket({ buf, pos: packet.pos, dir: packet.dir })
	}
}
class JitterBuffer {
	private audioCtx: AudioContext
	private panner: PannerNode
	private latency: number
	private startingTime: number
	private packetsPlayed: number
	private lastPlayed: number

	constructor(audioCtx: AudioContext) {
		this.latency = 4
		this.audioCtx = audioCtx
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
	}

	connect(node: AudioNode): AudioNode {
		return this.panner.connect(node)
	}
	pushPacket(packet: PosAudioPacket) {
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
	private playAudioPacket(packet: PosAudioPacket, timeS: number) {
		const { pos, dir, buf } = packet

		this.panner.positionX.setValueAtTime(pos.x, timeS)
		this.panner.positionY.setValueAtTime(pos.y, timeS)
		this.panner.positionZ.setValueAtTime(pos.z, timeS)

		this.panner.orientationX.setValueAtTime(dir.x, timeS)
		this.panner.orientationY.setValueAtTime(dir.y, timeS)
		this.panner.orientationZ.setValueAtTime(dir.z, timeS)

		const audio = this.audioCtx.createBufferSource()
		audio.buffer = buf
		audio.connect(this.panner)
		audio.start(timeS)
	}
}

function extractOpusFrames(arrayBuffer: ArrayBufferLike): Uint8Array {
	const view = new DataView(arrayBuffer)
	let offset = 0
	const opusFrames: Uint8Array[] = []

	while (offset < view.byteLength) {
		if (
			view.getUint8(offset) === 0x4f &&
			view.getUint8(offset + 1) === 0x67 &&
			view.getUint8(offset + 2) === 0x67 &&
			view.getUint8(offset + 3) === 0x53
		) {
			const segmentCount = view.getUint8(offset + 26)
			const segmentTable = new Uint8Array(arrayBuffer, offset + 27, segmentCount)
			let segmentOffset = offset + 27 + segmentCount

			for (let i = 0; i < segmentCount; i++) {
				const segmentSize = segmentTable[i]
				if (segmentSize > 0) {
					opusFrames.push(new Uint8Array(arrayBuffer, segmentOffset, segmentSize))
					segmentOffset += segmentSize
				}
			}

			offset = segmentOffset
		} else {
			break
		}
	}

	return combineUint8Arrays(opusFrames)
}
function combineUint8Arrays(arrays: Uint8Array[]): Uint8Array {
	const totalLength = arrays.reduce((acc, curr) => acc + curr.length, 0)
	const combinedArray = new Uint8Array(totalLength)

	let offset = 0
	for (const array of arrays) {
		combinedArray.set(array, offset)
		offset += array.length
	}

	return combinedArray
}
