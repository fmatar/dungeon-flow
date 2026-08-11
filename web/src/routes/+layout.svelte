<script lang="ts">
	import '../app.css';
	import { page } from '$app/state';

	let { children } = $props();

	const nav = [
		{ href: '/', label: 'play' },
		{ href: '/race', label: 'race' }
	];
</script>

<div class="crt min-h-screen bg-surface-950 text-surface-100">
	<header class="border-b border-surface-800 bg-surface-950/80 backdrop-blur">
		<div class="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
			<a href="/" class="font-mono text-lg text-primary-400 text-glow">
				▚ dungeon-flow<span class="cursor"></span>
			</a>
			<nav class="flex gap-2 font-mono text-sm">
				{#each nav as item (item.href)}
					{@const active = page.url.pathname === item.href}
					<a
						href={item.href}
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
