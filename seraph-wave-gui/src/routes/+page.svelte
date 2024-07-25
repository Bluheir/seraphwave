<script lang="ts">
    import { Plus } from "lucide-svelte"
    import type { PageData } from "./$types"
	import Navbar from "$lib/components/Navbar.svelte"
	export let data: PageData

    $: accountList = Array.from(data.accounts.values())
</script>

<Navbar />
<div class="grid gap-1 grid-cols-2 sm:grid-cols-3 md:grid-cols-4 xl:grid-cols-5 text-base-content mx-3 auto-rows-fr">
    {#each accountList as account}
    <a
        class="bg-base-200 p-1 box-border prose rounded-lg flex items-center flex-col btn btn-ghost h-full"
        href="/account?{new URLSearchParams({ uuid: account.uuid })}"
    >
        <img src="https://crafatar.com/avatars/{account.uuid}" alt=""/>
        <h3 class="overflow-x-hidden m-0">{account.username}</h3>
    </a>
    {/each}
    <a class="bg-base-200 prose rounded-lg flex items-center justify-center gap-2 p-1 hover:bg-base-300 transition h-[17em]" href="/create">
        <Plus class="h-7 w-7"/>
        <h2 class="m-0">Add an account</h2>
    </a>
</div>