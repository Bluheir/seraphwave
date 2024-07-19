<script lang="ts">
	import Navbar from "$lib/components/Navbar.svelte"
	import type { AccountInfo } from "../+layout"
	import type { PageData } from "./$types"
	import { apiImpl, AudioManager, type VoiceClient, clientInstance } from "$lib/api"

	import { MediaRecorder, register } from "extendable-media-recorder"
	import { connect } from "extendable-media-recorder-wav-encoder"
	import { onMount } from "svelte"

	export let data: PageData & { accountInfo: AccountInfo }
	let micVolume: number = 100
	let outVolume: number = 100
	$: inVc = $clientInstance !== undefined

	async function startSessionCode(uuid: string, code: string): Promise<void> {
		const client = await apiImpl.createClient(data.metaInfo, { type: "full", uuid, code })

		client.onConnect(async () => {
			$clientInstance = client
			await startConn(client)
		})
		client.onError(e => {
			console.log(e)
		})
	}
	async function startConn(client: VoiceClient) {
		let audioCtx = new window.AudioContext()

		await register(await connect())
		const audioMgr = await AudioManager.createAudioMgr(
			(stream) => new MediaRecorder(stream, { mimeType: "audio/wav" }) as any,
			client,
			audioCtx,
		)
	}

	onMount(async () => {
		if (inVc) {
			await startConn($clientInstance!)
		}
	})
</script>

<Navbar />
<div class="my-5 px-1 w-full">
	<div class="select-none max-w-[95ch] bg-base-200 py-8 px-5 rounded-lg mx-auto">
		<div class="md:flex block md:gap-8">
			<div class="md:contents flex justify-center mb-8 w-full">
				<img
					src="https://crafatar.com/avatars/{data.accountInfo.uuid}"
					alt=""
					class="w-[12em] h-[12em]"
				/>
			</div>
			<div class="prose flex-grow max-w-full">
				{#if inVc}
					<h2 class="mt-0">Microphone volume</h2>
					<input
						type="range"
						min="0"
						max="100"
						bind:value={micVolume}
						class="range range-success w-[min(25em,100%)] range-xs mb-4"
					/>
					<h2 class="mt-0">Output volume</h2>
					<input
						type="range"
						min="0"
						max="100"
						bind:value={outVolume}
						class="range range-success w-[min(25em,100%)] range-xs"
					/>
				{:else}
					<h2 class="mt-0">Join proximity chat</h2>
					<p>To be able to speak in proximity chat, you need to join the proximity chat server.</p>
				{/if}
			</div>
		</div>
		<div class="mt-5 gap-8 flex">
			<div class="flex items-center justify-center w-[12em]">
				<h1 class="text-3xl">{data.accountInfo.username}</h1>
			</div>
			{#if !inVc}
				<button
					class="btn btn-accent flex-grow"
					on:click={async () =>
						await startSessionCode(data.accountInfo.uuid, data.accountInfo.code)}
					>Join proximity chat</button
				>
			{/if}
		</div>
	</div>
</div>
