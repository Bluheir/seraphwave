<script lang="ts">
	import { apiImpl } from "$lib/api"
	import type { PageData } from './$types';

	import Coder from "$lib/components/CodeCreator.svelte"

	export let data: PageData
</script>


<div class="h-full w-full flex justify-center items-center">
    <Coder welcomeMsg={data.metaInfo.welcomeMsg} codeGen={apiImpl}/>
</div>
