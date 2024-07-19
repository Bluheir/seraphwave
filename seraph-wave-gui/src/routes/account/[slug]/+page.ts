import { error } from "@sveltejs/kit";
import type { PageLoad } from "./$types";

export const prerender = true

export const load: PageLoad = async ({ params, parent }) => {
    const data = await parent()
    const accountInfo = data.accounts.get(params.slug)

    if(!accountInfo){
        error(404, "not found")
    }

	return { accountInfo }
};