<script lang="ts">
	import { Check, Eye, EyeOff } from "lucide-svelte"
	import { toast } from "svelte-sonner"
	import { Toggle } from "bits-ui"

	export let code: string

	let showCode = true

	$: displayCode = updateCode(showCode)

	const updateCode = (showCode: boolean) => {
		if (!showCode) {
			return "&&&-&&&-&&&"
		} else {
			return code
		}
	}

	const copyCode = () => {
		toast("Command copied to clipboard!", {
			icon: Check,
		})

		navigator.clipboard.writeText(`/joinprox ${code}`)
	}
	const handleCopy = (event: ClipboardEvent) => {
		// Prevent the default copy action
		event.preventDefault()

		// Set the clipboard content
		if (event.clipboardData) {
			event.clipboardData.setData("text/plain", `/joinprox ${code}`)
		}
	}
</script>

<h2>Your code is:</h2>
<div class="flex btn btn-neutral p-0 border-0 hover:border-0 checked:border-0 gap-0" id="code-display">
	<button
		class="w-full flex-1 border-0 select-all rounded-tr-none rounded-br-none h-full text-2xl checked:border-0"
		on:click={copyCode}
		title="Copy command to clipboard"
	>
		{displayCode}
	</button>
	<Toggle.Root
		aria-label="Toggle code visibility"
		class="btn bg-base-100 text-base-content hover:bg-base-100/70 hover:border-0 border-0 checked:border-0 rounded-bl-none rounded-tl-none"
		bind:pressed={showCode}
		title={showCode ? "Click to hide code" : "Click to show code"}
	>
		{#if showCode}
			<Eye />
		{:else}
			<EyeOff />
		{/if}
	</Toggle.Root>
</div>
<p>Type the following command in Minecraft to use proximity chat.</p>
<pre on:copy={handleCopy}><code>/joinprox <span class="text-secondary">{displayCode}</span></code
	></pre>
<em>Waiting for the command to be entered...</em>
