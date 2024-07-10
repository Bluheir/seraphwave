/** @type {import('tailwindcss').Config} */
export default {
	content: ["./src/**/*.{html,js,svelte}"],
	theme: {
		extend: {},
	},
	daisyui: {
		themes: [
			"light",
			"dark",
			"synthwave",
			"cyberpunk",
			"wireframe",
			"night",
			"luxury",
			"sunset",
			"coffee",
			"wireframe-dark",
			"black",
			{
				"cyberpunk-dark": {
					...require("daisyui/src/theming/themes")["cyberpunk"],
					...require("daisyui/src/theming/themes")["sunset"]
				},
				"base": {
					...require("daisyui/src/theming/themes")["black"],
					"--rounded-box": "0",
					"--rounded-btn": "0.5rem",
					"--rounded-badge": "99999px",
					"--animation-btn": ".25s",
					"--animation-input": ".2s",
					"--btn-text-case": "lowercase",
					"--navbar-padding": ".5rem",
					"--border-btn": "1px",
				}
			}
		]
	},
	plugins: [require("@tailwindcss/typography"), require("daisyui")],
}
