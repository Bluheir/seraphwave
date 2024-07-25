<script lang="ts">
	import type { CreateCode } from "$lib/api"
	import { fly } from "svelte/transition"
	import CommandWait from "./CommandWait.svelte"

	export let welcomeMsg: string
	export let codeGen: CreateCode

	let code: string | undefined = undefined

	export let onGenerate: (code: string) => Promise<void>
</script>

<div class="prose border-2 border-neutral p-9 rounded-md shadow">
	{#if code}
		<div in:fly={{ x: 300, duration: 100 }}>
			<h1>{welcomeMsg}</h1>
			<CommandWait {code} />
		</div>
	{:else}
		<h1>{welcomeMsg}</h1>
		<p>To use proximity chat on this server, you will need to generate a code.</p>
		<button
			class="btn btn-primary"
			on:click={async () => {
				code = await codeGen.createCode()
				await onGenerate(code)
			}}>Generate a code</button
		>
	{/if}
</div>
