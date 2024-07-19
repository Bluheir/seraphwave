<script lang="ts">
	import { apiImpl, clientInstance } from "$lib/api"
	import type { PageData } from "../$types"
	export let data: PageData

	import Coder from "$lib/components/CodeCreator.svelte"
	import { goto } from "$app/navigation"

	async function onGenerate(code: string): Promise<void> {
		const client = await apiImpl.createClient(data.metaInfo, { type: "temp", code })
		client.onSessionCode(async (sessionInfo) => {
			$clientInstance = client
			const newval = data.accounts.set(sessionInfo.uuid, sessionInfo)
			localStorage.setItem("accounts", JSON.stringify(newval))
			data.accounts = newval
			await goto(`/account/${sessionInfo.uuid}`)
		})
	}
</script>

<div class="h-full w-full flex justify-center items-center">
	<Coder welcomeMsg={data.metaInfo.welcomeMsg} codeGen={apiImpl} {onGenerate} />
</div>
