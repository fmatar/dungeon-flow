<script lang="ts">
	import { Game } from '$lib/game.svelte';
	import RoomMap from '$lib/components/RoomMap.svelte';
	import PrimitiveSpotlight from '$lib/components/PrimitiveSpotlight.svelte';
	import TorchTimer from '$lib/components/TorchTimer.svelte';
	import { ROOM_LABEL } from '$lib/rooms';

	const game = new Game();
</script>

<div class="grid gap-4 lg:grid-cols-2">
	<!-- LEFT: the terminal you play in -->
	<section class="card overflow-hidden border border-surface-800 bg-black/50 p-0">
		<div
			class="flex items-center justify-between border-b border-surface-800 px-4 py-2 font-mono text-xs"
		>
			<span class="text-surface-400">
				/dungeon{game.id ? `/${game.id.slice(0, 12)}…` : ''}
			</span>
			<span class="flex items-center gap-1.5">
				<span
					class="h-2 w-2 rounded-full {game.connected ? 'bg-primary-500' : 'bg-surface-600'}"
				></span>
				<span class="text-surface-500">{game.connected ? 'live' : 'offline'}</span>
			</span>
		</div>

		<div class="min-h-[18rem] p-5 font-mono">
			{#if !game.running}
				<p class="leading-relaxed text-surface-400">
					A containerized dungeon whose map <span class="text-primary-400">is</span> a running
					workflow. Start a game and watch each engine primitive happen — an event wait, a switch, a
					join, a retry, a timeout.
				</p>
				<button
					class="btn preset-filled-primary-500 mt-6"
					onclick={() => game.start()}
					disabled={game.busy}
				>
					{game.busy ? 'starting…' : '▶ start a new game'}
				</button>
			{:else if game.view}
				<div class="text-xs uppercase tracking-widest text-primary-500 text-glow">
					{ROOM_LABEL[game.view.room]}
				</div>
				{#key game.view.narrative}
					<p class="mt-3 leading-relaxed text-surface-100 line-in">{game.view.narrative}</p>
				{/key}
				<p class="mt-3 text-sm text-surface-400">// {game.view.hint}</p>

				<div class="mt-7">
					{#if game.done}
						<div
							class="rounded border border-primary-500 bg-primary-500/10 p-4 text-primary-300 text-glow"
						>
							✦ Victory — you cleared the dungeon. This instance is complete.
						</div>
						<button class="btn preset-filled-primary-500 mt-4" onclick={() => game.start()}>
							▶ play again
						</button>
					{:else if game.room === 'FORK'}
						<div class="flex flex-wrap items-center gap-5">
							<div class="flex gap-2">
								<button class="btn preset-filled-primary-500" onclick={() => game.choose('left')}>
									◀ go left
								</button>
								<button class="btn preset-filled-primary-500" onclick={() => game.choose('right')}>
									go right ▶
								</button>
							</div>
							<TorchTimer
								seconds={game.torchSeconds}
								resetKey={game.forkEntries}
								active={game.status === 'WAITING'}
							/>
						</div>
					{:else if game.room === 'LEVER_ROOM'}
						<div class="flex gap-2">
							<button
								class="btn {game.leverA ? 'preset-tonal-surface' : 'preset-filled-primary-500'}"
								disabled={game.leverA}
								onclick={() => game.lever('a')}
							>
								⎇ pull lever A
							</button>
							<button
								class="btn {game.leverB ? 'preset-tonal-surface' : 'preset-filled-primary-500'}"
								disabled={game.leverB}
								onclick={() => game.lever('b')}
							>
								⎇ pull lever B
							</button>
						</div>
					{:else if game.room === 'TRAP_CORRIDOR'}
						<div class="animate-pulse text-sm text-surface-400">picking the lock…</div>
					{/if}

					<button
						class="btn btn-sm preset-tonal-surface mt-5 text-surface-400"
						onclick={() => game.abandon()}
					>
						abandon
					</button>
				</div>
			{/if}

			{#if game.error}
				<p class="mt-4 text-sm text-error-400">! {game.error}</p>
			{/if}
		</div>
	</section>

	<!-- RIGHT: the teaching layer -->
	<section class="space-y-4">
		<PrimitiveSpotlight {game} />
		<div class="card border border-surface-800 bg-surface-950/60 p-4">
			<RoomMap current={game.room} victory={game.victory} />
		</div>
	</section>
</div>
