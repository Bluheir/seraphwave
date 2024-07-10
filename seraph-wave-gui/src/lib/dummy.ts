import { chunk, join, map } from "lodash-es"
import type { CreateCode, FullApi, GetMetaInfo, MetaInfo } from "./api"

function randomInt(min: number, max: number) {
	return Math.floor(Math.random() * (max - min)) + min
}

export class DummyImpl implements CreateCode, GetMetaInfo, FullApi {
	async getMeta(): Promise<MetaInfo> {
		return {
			welcomeMsg: "Welcome to proximity chat for the server!",
			altAccounts: true,
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
	
}
