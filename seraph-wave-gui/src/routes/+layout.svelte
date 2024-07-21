<script lang="ts">
	import { onMount } from "svelte"
    import "../app.pcss"
    import { Toaster } from "svelte-sonner"

    onMount(() => {
        const theme = localStorage.getItem("theme") || "cyberpunk-dark"
        const daddyElement = document.getElementsByTagName("html").item(0)!
        daddyElement.setAttribute("data-theme", theme)
    })
</script>

<Toaster toastOptions={{
    class: "bg-info text-info-content border-0"
}}/>
<slot />