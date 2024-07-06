/** @type {import('tailwindcss').Config} */
export default {
	content: [],
	theme: {
		extend: {},
	},
	daisyui: {
		themes: [
			"light",
			"synthwave",
			"cyberpunk",
			"wireframe",
			"night",
			"luxury",
			"sunset",
			"coffee",
			{
				"cyberpunk-dark": {
					...require("daisyui/src/theming/themes")["cyberpunk"],
					...require("daisyui/src/theming/themes")["sunset"]
				},
				"black": {
					...require("daisyui/src/theming/themes")["black"],
					"--animation-btn": ".25s",
					"--animation-input": ".2s",
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
	plugins: [require("daisyui")],
}
