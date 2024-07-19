import { error } from "@sveltejs/kit"
import type { PageLoad } from "./$types"

export const prerender = true

export const load: PageLoad = async ({ url, parent }) => {
	const selectedUuid = url.searchParams.get("uuid")

	if(!selectedUuid) {
		error(422, "missing uuid")
	}
	const data = await parent()
	const accountInfo = data.accounts.get(selectedUuid)

	if (!accountInfo) {
		error(404, "not found")
	}

	return { accountInfo }
}
