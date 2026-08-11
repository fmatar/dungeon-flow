import adapter from '@sveltejs/adapter-static';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

export default defineConfig({
	plugins: [
		tailwindcss(),
		sveltekit({
			compilerOptions: {
				runes: ({ filename }) =>
					filename.split(/[/\\]/).includes('node_modules') ? undefined : true
			},
			// SPA build: no server-side rendering, single index.html fallback. This lets the app
			// be served as static files - including straight from the Quarkus backend later, so the
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
});
