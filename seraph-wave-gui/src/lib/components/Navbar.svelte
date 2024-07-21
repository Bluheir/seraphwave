<script lang="ts">
	import { Dialog } from "bits-ui"
	import { Settings, X } from "lucide-svelte"
	import SettingsMenu from "./SettingsMenu.svelte"
	import { fade } from "svelte/transition"
</script>

<nav class="navbar p-4">
	<div class="navbar-start">
		<Dialog.Root>
			<Dialog.Trigger class="btn btn-circle">
				<Settings />
			</Dialog.Trigger>
			<Dialog.Portal>
				<Dialog.Overlay
					transition={fade}
					transitionConfig={{ duration: 150 }}
					class="fixed inset-0 z-50 bg-black/80"
				/>
				<Dialog.Content class="fixed left-[50%] top-[40%] z-50 w-full max-w-[94%] translate-x-[-50%] translate-y-[-50%] rounded-card-lg border bg-background p-5 shadow-popover outline-none sm:max-w-[490px] md:w-full rounded-box border-neutral bg-base-100">
                    <SettingsMenu />
					<Dialog.Close
						class="absolute right-5 top-5 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-foreground focus-visible:ring-offset-2 focus-visible:ring-offset-background active:scale-98"
					>
						<div>
							<X class="size-5 text-foreground" />
							<span class="sr-only">Close</span>
						</div>
					</Dialog.Close>
				</Dialog.Content>
			</Dialog.Portal>
		</Dialog.Root>
	</div>
	<div class="navbar-center">
		<a class="btn text-lg font-black" href="/">Home</a>
	</div>
	<div class="navbar-end" />
</nav>
