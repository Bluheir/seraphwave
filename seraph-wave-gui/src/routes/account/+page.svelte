<script lang="ts">
	import Navbar from "$lib/components/Navbar.svelte"
	import type { AccountInfo } from "../+layout"
	import type { PageData } from "./$types"
	import { apiImpl, AudioManager, type VoiceClient, clientInstance, audioInstance } from "$lib/api"

	import { onMount } from "svelte"
	import { beforeNavigate, goto } from "$app/navigation"

	export let data: PageData & { accountInfo: AccountInfo }
	let inVolume: number = 100
	let outVolume: number = 100

	function linearizeGain(gain: number): number {
		return (Math.exp(gain) - 1) / (Math.E - 1)
	}

	beforeNavigate(async _ => {
		if($audioInstance) {
			await $audioInstance.stopAudio()
		}
		$clientInstance = undefined
	})

	let errorState: "ok" | "cannotConnect" | "codeConsumed" | "disconnected" = "ok"

	$: if($audioInstance) { $audioInstance.setInGain(linearizeGain(inVolume / 100))}
	$: if($audioInstance) { $audioInstance.setOutGain(linearizeGain(outVolume / 100)) }

	$: inVc = $clientInstance !== undefined

	async function startSessionCode(): Promise<void> {
		const { uuid, code } = data.accountInfo
		let client: VoiceClient

		try {
			client = await apiImpl.createClient(data.metaInfo, { type: "full", uuid, code })
		} catch(e) {
			errorState = "cannotConnect"
			return
		}

		client.onConnect(async () => {
			errorState = "ok"
			$clientInstance = client
			await startConn(client)
		})
		client.onError(async e => {
			console.error(e)

			switch(e.errorCode) {
				case 1:
					errorState = "codeConsumed"
					break
				// an invalid session code implies that the session code has expired or was replaced by another
				case 2:
					// delete the expired session code
					data.accounts.delete(uuid)
					localStorage.setItem("accounts", JSON.stringify(Object.entries(data.accounts.entries())))
					await goto("/")
					break
				default:
					break
			}
		})
		client.onClose(async () => {
			if(errorState === "ok") {
				errorState = "disconnected"
			}
			if($audioInstance) {
				await $audioInstance.stopAudio()
			}
			$clientInstance = undefined
		})
	}
	async function startConn(client: VoiceClient) {
		if(!$audioInstance) {
			const c = window.AudioContext || (window as any).webkitAudioContext
			let audioCtx = new c()
			$audioInstance = await AudioManager.createAudioMgr(
				audioCtx
			)
		}

		$audioInstance.hookEvents(client)
		await $audioInstance.startAudio()
	}

	onMount(async () => {
		if (inVc) {
			await startConn($clientInstance!)
		}
	})
</script>

<Navbar />
<div class="my-5 px-1 w-full">
	<div class="select-none max-w-[95ch] border-neutral border p-7 rounded-box mx-auto md:grid gap-y-5 gap-x-8 grid-rows-[1fr_auto] grid-cols-[auto_1fr] grid-flow-col">
		<div class="md:contents flex w-full justify-center my-4"><img
			src="https://crafatar.com/avatars/{data.accountInfo.uuid}"
			alt=""
			class="w-[13em] h-[13em]"
		/></div>
		<div class="flex justify-center items-center my-4 md:my-0"><h1 class="text-3xl">{data.accountInfo.username}</h1></div>
		<div class="prose max-w-full flex-grow">
			{#if inVc && errorState === "ok"}
				<h2 class="mt-0">Microphone volume</h2>
				<input
					type="range"
					min="0"
					max="100"
					bind:value={inVolume}
					class="range range-success w-[min(25em,100%)] range-xs"
				/>
				<h2 class="mt-4">Output volume</h2>
				<input
					type="range"
					min="0"
					max="100"
					bind:value={outVolume}
					class="range range-success w-[min(25em,100%)] range-xs"
				/>
			{:else if errorState === "ok"}
				<h2>Join proximity chat</h2>
				<p class="mb-4 md:mb-0">To be able to speak in proximity chat, you need to join the proximity chat server.</p>
			{:else if errorState === "cannotConnect"}
				<h2>Cannot connect</h2>
				<p class="mb-4 md:mb-0">Cannot connect to proximity chat server. This may be due to the Minecraft server being offline.</p>
			{:else if errorState === "codeConsumed"}
				<h2>Another client is connected on this account</h2>
				<p class="mb-4 md:mb-0">Another client is connected to the proximity chat server using this account. Please close the tab that is connected before reconnecting.</p>
			{:else if errorState === "disconnected"}
				<h2>Disconnected</h2>
				<p class="mb-4 md:mb-0">You have been disconnected from the proximity chat server. This may be due to the Minecraft server turning off.</p>
			{/if}
		</div>
		{#if inVc}
		<div/>
		{:else if errorState === "ok"}
			<button
				class="btn btn-accent"
				on:click={async () => await startSessionCode()}
			>Join proximity chat</button>
		{:else if errorState === "cannotConnect" || errorState === "codeConsumed" || errorState === "disconnected"}
			<button
				class="btn btn-error"
				on:click={async () => await startSessionCode()}
			>Reconnect</button>
		{/if}
	</div>
</div>
