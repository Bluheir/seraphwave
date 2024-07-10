import type { CreateCode, FullApi, GetMetaInfo, MetaInfo } from "./api";

export class MainImpl implements CreateCode, GetMetaInfo, FullApi {
    async createCode(): Promise<string> {
        const request = new Request("/code", {
            method: "POST",
        })

        return (await (await fetch(request)).json()).code
    }
    async getMeta(): Promise<MetaInfo> {
        return await (await fetch("/meta")).json()
    }
}