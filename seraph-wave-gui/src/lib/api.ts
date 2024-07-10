import { DummyImpl } from "./dummy"
import { MainImpl } from "./mainimpl"

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
	welcomeMsg: string,
	/**
	 * If alt accounts are supported.
	 */
	altAccounts: boolean,
}

export interface GetMetaInfo {
	getMeta(): Promise<MetaInfo>
}

export interface FullApi extends CreateCode, GetMetaInfo {

}

export const apiImpl: FullApi = new MainImpl()