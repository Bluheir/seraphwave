export const ssr = false
export const prerender = true
export const csr = true

import { apiImpl } from "$lib/api"

export type AccountInfo = {
	code: string
	uuid: string
	username: string
}

export const load = async () => {
	let accounts: Map<string, AccountInfo>
	const s = localStorage.getItem("accounts")

	if (s) {
		try {
			accounts = new Map(Object.entries(JSON.parse(s)))
		} catch {
			console.error("accounts storage got corrupted")
			localStorage.setItem("accounts", "{}")
			accounts = new Map()
		}
	} else {
		localStorage.setItem("accounts", "{}")
		accounts = new Map()
	}

	const metaInfo = await apiImpl.getMeta()

	return {
		metaInfo,
		accounts,
	}
}
