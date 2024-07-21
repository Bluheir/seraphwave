<script lang="ts">
    import { DropdownMenu } from "bits-ui";
	import { ChevronDown } from "lucide-svelte"

    let availableThemes = ["cyberpunk-dark", "light", "dark", "cupcake", "synthwave", "base"]

    function setTheme(theme: string) {
        const daddyElement = document.getElementsByTagName("html").item(0)!
        daddyElement.setAttribute("data-theme", theme)
        localStorage.setItem("theme", theme)
    }
</script>

<div class="prose">
    <h2>Settings</h2>
    <DropdownMenu.Root>
        <DropdownMenu.Trigger>
            <button class="btn">Theme<ChevronDown class="w-5 transition"/></button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Content class="px-3.5 bg-base-200 rounded-box overflow-y-auto 2xl:max-h-[21em] xl:max-h-[17em] max-h-[9em]">
        {#each availableThemes as theme}
        <div data-theme={theme} class="rounded-btn my-4">
            <button class="btn btn-ghost w-[14rem] rounded-btn py-3 flex px-5" on:click={() => setTheme(theme)} title="Select this theme">
            <span class="flex-grow inline-flex justify-start">{theme}</span>
            <span class="flex h-full gap-1">
                <span class="bg-primary rounded-badge w-2" />
                <span class="bg-secondary rounded-badge w-2" />
                <span class="bg-accent rounded-badge w-2" />
                <span class="bg-neutral rounded-badge w-2" />
            </span>
            </button>
        </div>
        {/each}
        </DropdownMenu.Content>
    </DropdownMenu.Root>
</div>