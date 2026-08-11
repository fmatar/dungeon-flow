<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';
	import { base } from '$app/paths';

	let { children } = $props();

	// Paths are declared WITHOUT the base and prefixed with it below. SvelteKit does not rewrite
	// hardcoded href attributes, so a literal href="/race" would resolve against the origin root and
	// escape the app entirely when it is mounted under a path prefix (as it is on the DataRobot
	// Workload API). `base` is '' locally, so this is identical there.
	const nav = [
		{ path: '/', label: 'play' },
		{ path: '/race', label: 'race' }
	];
</script>

<div class="crt min-h-screen bg-surface-950 text-surface-100">
	<header class="border-b border-surface-800 bg-surface-950/80 backdrop-blur">
		<div class="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
			<a href="{base}/" class="font-mono text-lg text-primary-400 text-glow">
				▚ dungeon-flow<span class="cursor"></span>
			</a>
			<nav class="flex gap-2 font-mono text-sm">
				{#each nav as item (item.path)}
					<!-- page.url.pathname carries the base, so compare against the prefixed path -
					     comparing to the bare path would silently break the active state under a mount. -->
					{@const active = page.url.pathname === `${base}${item.path}`}
					<a
						href="{base}{item.path}"
						class="btn btn-sm {active
							? 'preset-filled-primary-500'
							: 'preset-tonal-surface text-surface-300'}"
					>
						{item.label}
					</a>
				{/each}
			</nav>
		</div>
	</header>

	<main class="mx-auto max-w-6xl p-4">
		{@render children()}
	</main>

	<footer class="mx-auto max-w-6xl px-4 py-6 text-center font-mono text-[0.7rem] text-surface-600">
		the map is a CNCF Serverless Workflow · powered by Quarkus Flow
	</footer>
</div>
