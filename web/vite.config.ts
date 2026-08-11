import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

// The config is a function of `command` so the mount-point sentinel applies to
// production builds ONLY. SvelteKit honours `paths.base` in dev too, so setting it
// unconditionally serves `vite dev` at http://localhost:5173/__DR_BASE__ and leaves
// the root 404ing - a confusing break for anyone running the dev server.
export default defineConfig(({ command }) => ({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			paths: {
				// SvelteKit compiles `base` into the client bundle at BUILD time, but the app's
				// real mount point is only known at RUN time: on the DataRobot Workload API it is
				// served under /api/v2/endpoints/workloads/<id>/, and that id does not exist until
				// the workload does. Building with a sentinel and substituting it at container
				// startup keeps ONE image deployable anywhere — see SpaFallbackRoute.java.
				//
				// This is not cosmetic: without a correct `base` the client router compares the
				// real pathname against '' and rejects even the initial URL with
				// "Not found: /api/v2/endpoints/workloads/<id>/", leaving a blank page however
				// correct the asset URLs are.
				//
				// In dev there is no substitution step, so base stays empty and the app is served
				// from the root as you would expect.
				base: command === 'build' ? '/__DR_BASE__' : ''
			},
			// SPA build: no server-side rendering, single index.html fallback. This lets the app
			// be served as static files — including straight from the Quarkus backend, so the
			// whole game stays a single container (SRS constraint C-3).
			adapter: adapter({ fallback: 'index.html' })
		})
	],
	server: {
		// Dev-only proxy: the browser stays same-origin on :5173 and calls /api/*, which is
		// forwarded to the Quarkus backend on :8080. Avoids CORS and works for SSE streams too.
		// NOTE: no rewrite. The backend serves its REST endpoints under /api itself
		// (quarkus.rest.path=/api), so the prefix must be passed through, not stripped.
		proxy: {
			'/api': {
				target: 'http://localhost:8080',
				changeOrigin: true
			}
		}
	}
}));
