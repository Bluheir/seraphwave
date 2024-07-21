<script lang="ts">
	import Navbar from "$lib/components/Navbar.svelte"
	import type { AccountInfo } from "../+layout"
	import type { PageData } from "./$types"
	import { apiImpl, AudioManager, type VoiceClient, clientInstance, audioInstance } from "$lib/api"

	import { MediaRecorder, register } from "extendable-media-recorder"
	import { connect } from "extendable-media-recorder-wav-encoder"
	import { onMount } from "svelte"
	import { goto } from "$app/navigation"

	export let data: PageData & { accountInfo: AccountInfo }
	let inVolume: number = 100
	let outVolume: number = 100

	function linearizeGain(gain: number): number {
		return (Math.exp(gain) - 1) / (Math.E - 1)
	}

	$: if($audioInstance) { $audioInstance.setInGain(linearizeGain(inVolume / 100))}
	$: if($audioInstance) { $audioInstance.setOutGain(linearizeGain(outVolume / 100)) }

	$: inVc = $clientInstance !== undefined
	$: inAudio = $audioInstance !== undefined

	async function startSessionCode(): Promise<void> {
		const { uuid, code } = data.accountInfo
		const client = await apiImpl.createClient(data.metaInfo, { type: "full", uuid, code })

		client.onConnect(async () => {
			$clientInstance = client
			await startConn(client)
		})
		client.onError(async e => {
			console.log(e)

			switch(e.errorCode) {
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
		client.onClose(() => {
			if($audioInstance) {
				$audioInstance.stopAudio()
			}
			$audioInstance = undefined
			$clientInstance = undefined
		})
	}
	async function startConn(client: VoiceClient) {
		let audioCtx = new window.AudioContext()

		await register(await connect())
		$audioInstance = await AudioManager.createAudioMgr(
			(stream) => new MediaRecorder(stream, { mimeType: "audio/wav" }) as any,
			client,
			audioCtx,
		)
	}

	onMount(async () => {
		if (inVc && !inAudio) {
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
			{#if inVc}
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
			{:else}
				<h2>Join proximity chat</h2>
				<p class="mb-4 md:mb-0">To be able to speak in proximity chat, you need to join the proximity chat server.</p>
			{/if}
		</div>
		{#if !inVc}
			<button
				class="btn btn-accent"
				on:click={async () =>
					await startSessionCode()}
				>Join proximity chat</button
			>
		{:else}
		<div/>
		{/if}
	</div>
</div>
