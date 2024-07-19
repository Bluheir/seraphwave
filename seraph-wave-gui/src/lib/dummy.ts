import { chunk, join, map } from "lodash-es"
import type { CreateCode, FullApi, GetMetaInfo, InitialConnState, MetaInfo, VoiceClient, VoiceClientMgr } from "./api"
import { MainImpl } from "./mainimpl"

function randomInt(min: number, max: number) {
	return Math.floor(Math.random() * (max - min)) + min
}

export class DummyImpl implements CreateCode, GetMetaInfo, VoiceClientMgr, FullApi {
	async getMeta(): Promise<MetaInfo | any> {
		return {
			welcomeMsg: "Welcome to proximity chat for the server!",
			altAccounts: true,
			webSocketUrl: "ws://192.168.2.20:65437/gateway"
		}
	}
	async createCode(): Promise<string> {
		const alphanumerics = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"

		const value = join(
			map(chunk(Array(9), 3), (chunk) =>
				join(
					chunk.map((_) => alphanumerics[randomInt(0, alphanumerics.length)]),
					"",
				),
			),
			"-",
		)
        
		return value
	}
	createClient(info: MetaInfo | any, initialState: InitialConnState): Promise<VoiceClient> {
		return new MainImpl().createClient(info, initialState)
	}
}
