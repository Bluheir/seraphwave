<script lang="ts">
    import "../app.pcss"
    import { Toaster } from "svelte-sonner"
</script>

<Toaster toastOptions={{
    class: "bg-info text-info-content border-0"
}}/>
<slot />