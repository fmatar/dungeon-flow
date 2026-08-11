<script lang="ts">
	import type { Game } from '$lib/game.svelte';

	let { game }: { game: Game } = $props();

	const s = $derived(game.spotlight);
</script>

<div class="card border border-surface-800 bg-surface-950/60 p-4">
	<div class="flex items-center gap-2">
		<span class="badge preset-filled-primary-500 font-mono text-xs">NOW</span>
		<span class="font-mono text-primary-400 text-glow">{s.tag}</span>
	</div>

	<h3 class="mt-3 font-mono text-lg text-glow text-primary-300">{s.title}</h3>
	<p class="mt-1 text-sm leading-relaxed text-surface-300">{s.blurb}</p>

	{#if s.dsl}
		<pre class="mt-3 overflow-x-auto rounded border border-surface-800 bg-black/60 p-2 text-[0.72rem] leading-relaxed text-primary-400"><code
			>{s.dsl}</code
		></pre>
	{/if}

	{#if game.room === 'LEVER_ROOM'}
		<div class="mt-3 flex gap-2 font-mono text-xs">
			<span
				class="flex-1 rounded border px-2 py-1 text-center {game.leverA
					? 'border-primary-500 bg-primary-500/15 text-primary-300 text-glow'
					: 'border-surface-700 text-surface-500'}"
			>
				lever A {game.leverA ? '▮ up' : '▯ down'}
			</span>
			<span
				class="flex-1 rounded border px-2 py-1 text-center {game.leverB
					? 'border-primary-500 bg-primary-500/15 text-primary-300 text-glow'
					: 'border-surface-700 text-surface-500'}"
			>
				lever B {game.leverB ? '▮ up' : '▯ down'}
			</span>
		</div>
	{/if}

	{#if game.room === 'TRAP_CORRIDOR' && game.attempts.length > 0}
		<div class="mt-3 space-y-1 font-mono text-xs">
			{#each game.attempts as a (a.attempt)}
				<div class="flex items-center gap-2 line-in">
					<span class="text-surface-500">attempt {a.attempt}/3</span>
					{#if a.picked}
						<span class="text-primary-400 text-glow">✔ CLICK — it opens</span>
					{:else}
						<span class="text-error-400">✘ JAMMED</span>
					{/if}
				</div>
			{/each}
		</div>
	{/if}
</div>
