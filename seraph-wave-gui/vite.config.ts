import { defineConfig } from "vite"
import { sveltekit } from "@sveltejs/kit/vite"
import { NodeGlobalsPolyfillPlugin } from "@esbuild-plugins/node-globals-polyfill"
import wasm from "vite-plugin-wasm"
import topLevelAwait from "vite-plugin-top-level-await"
import basicSsl from "@vitejs/plugin-basic-ssl"

export default defineConfig({
	plugins: [
		sveltekit(),
		wasm(),
		topLevelAwait(),
		basicSsl({
			name: "seraphwave",
			domains: ["seraphwave"],
		}),
	],
	server: {
		proxy: {},
	},
	optimizeDeps: {
		esbuildOptions: {
			define: {
				global: "globalThis",
			},
			plugins: [
				NodeGlobalsPolyfillPlugin({
					buffer: true,
				}),
			],
		},
	},
})
