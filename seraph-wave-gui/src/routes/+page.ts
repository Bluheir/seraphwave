import { apiImpl } from "$lib/api"

export const load = async () => {
    return {
        metaInfo: await apiImpl.getMeta()
    }    
}