<script lang="ts">
	import { Game } from '$lib/game.svelte';
	import RoomMap from '$lib/components/RoomMap.svelte';
	import PrimitiveSpotlight from '$lib/components/PrimitiveSpotlight.svelte';
	import TorchTimer from '$lib/components/TorchTimer.svelte';
	import RiddleGate from '$lib/components/RiddleGate.svelte';
	import { ROOM_LABEL } from '$lib/rooms';
	import { dur, ease, gsap } from '$lib/motion';

	const game = new Game();
	let selectedClass = $state('warrior');

	// Room changes get a deliberate beat: the label wipes in, the narrative settles behind it. This is
	// the app's core feedback — the engine moved you — so it should never be a silent text swap.
	let roomLabel = $state<HTMLElement | null>(null);
	let narrative = $state<HTMLParagraphElement | null>(null);

	// One authored moment per room change: the label resolves as the narrative settles under it. They
	// share a timeline so it reads as the room arriving, not as two elements animating near each other.
	$effect(() => {
		const room = game.room;
		if (!room || !roomLabel) return;
		const tl = gsap.timeline();
		tl.fromTo(
			roomLabel,
			{ opacity: 0, letterSpacing: '0.55em' },
			{ opacity: 1, letterSpacing: '0.2em', duration: dur.normal, ease: ease.settle }
		);
		if (narrative) {
			tl.fromTo(
				narrative,
				{ opacity: 0, y: 5 },
				{ opacity: 1, y: 0, duration: dur.normal, ease: ease.settle },
				'-=0.34'
			);
		}
		return () => tl.kill();
	});

	// Victory is the run's payoff and the only overshoot in the app.
	let victoryBanner = $state<HTMLElement | null>(null);
	$effect(() => {
		if (!game.done || !victoryBanner) return;
		const tl = gsap.fromTo(
			victoryBanner,
			{ scale: 0.96, opacity: 0 },
			{ scale: 1, opacity: 1, duration: dur.normal, ease: ease.snap }
		);
		return () => tl.kill();
	});
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
					workflow. Select your character class to modify your stats and unlock special paths:
				</p>
				
				<!-- Class Selection Grid -->
				<div class="grid gap-3 sm:grid-cols-3 mt-4">
					<button
						class="card p-3 border text-left flex flex-col justify-between transition-all {selectedClass === 'warrior' ? 'border-primary-500 bg-primary-500/5' : 'border-surface-800 bg-black/25 opacity-70 hover:opacity-100'}"
						onclick={() => selectedClass = 'warrior'}
					>
						<div>
							<div class="text-sm font-bold text-surface-100 uppercase">🛡️ Warrior</div>
							<div class="text-xs text-surface-400 mt-1 leading-normal">
								STR: 18 | DEX: 10 | INT: 8
							</div>
						</div>
						<div class="text-[10px] text-primary-400/80 mt-3 font-sans leading-snug">
							★ Bypasses Riddle Gates using Strength
						</div>
					</button>

					<button
						class="card p-3 border text-left flex flex-col justify-between transition-all {selectedClass === 'mage' ? 'border-primary-500 bg-primary-500/5' : 'border-surface-800 bg-black/25 opacity-70 hover:opacity-100'}"
						onclick={() => selectedClass = 'mage'}
					>
						<div>
							<div class="text-sm font-bold text-surface-100 uppercase">🔮 Mage</div>
							<div class="text-xs text-surface-400 mt-1 leading-normal">
								STR: 8 | DEX: 10 | INT: 18
							</div>
						</div>
						<div class="text-[10px] text-primary-400/80 mt-3 font-sans leading-snug">
							★ Solves riddles with near-miss answers
						</div>
					</button>

					<button
						class="card p-3 border text-left flex flex-col justify-between transition-all {selectedClass === 'rogue' ? 'border-primary-500 bg-primary-500/5' : 'border-surface-800 bg-black/25 opacity-70 hover:opacity-100'}"
						onclick={() => selectedClass = 'rogue'}
					>
						<div>
							<div class="text-sm font-bold text-surface-100 uppercase">🗡️ Rogue</div>
							<div class="text-xs text-surface-400 mt-1 leading-normal">
								STR: 10 | DEX: 18 | INT: 8
							</div>
						</div>
						<div class="text-[10px] text-primary-400/80 mt-3 font-sans leading-snug">
							★ 100% lockpick rate, bypassing traps
						</div>
					</button>
				</div>

				<button
					class="btn preset-filled-primary-500 mt-6 w-full sm:w-auto"
					onclick={() => game.start(selectedClass)}
					disabled={game.busy}
				>
					{game.busy ? 'starting…' : `▶ start game as ${selectedClass.toUpperCase()}`}
				</button>
			{:else if game.view}
				<div class="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-surface-800/40 pb-3 mb-3">
					<div
						bind:this={roomLabel}
						class="text-xs uppercase tracking-widest text-primary-500 text-glow"
					>
						{ROOM_LABEL[game.view.room]}
					</div>
					{#if game.stats}
						<div class="flex flex-wrap items-center gap-2 text-[10px] sm:text-xs border border-surface-800 px-3 py-1 rounded bg-surface-950/40 text-surface-300">
							<span class="uppercase font-bold text-primary-400">{game.playerClass}</span>
							<span class="text-surface-700">|</span>
							<span>💪 STR: <span class="text-glow text-surface-100">{game.stats.strength}</span></span>
							<span>🎯 DEX: <span class="text-glow text-surface-100">{game.stats.dexterity}</span></span>
							<span>🧠 INT: <span class="text-glow text-surface-100">{game.stats.intellect}</span></span>
						</div>
					{/if}
				</div>
				{#key game.view.narrative}
					<p bind:this={narrative} class="mt-3 leading-relaxed text-surface-100">
						{game.view.narrative}
					</p>
				{/key}
				<p class="mt-3 text-sm text-surface-400">// {game.view.hint}</p>

				<div class="mt-7">
					{#if game.done}
						<div
							bind:this={victoryBanner}
							class="rounded border border-primary-500 bg-primary-500/10 p-4 text-primary-300 text-glow"
						>
							✦ Victory — you cleared the dungeon. This instance is complete.
						</div>
						<button class="btn preset-filled-primary-500 mt-4" onclick={() => game.start(selectedClass)}>
							▶ play again
						</button>
					{:else if game.gated}
						<!-- The gate owns the interaction while it holds the door; the fork buttons
						     would be a lie here, because the direction is already chosen. -->
						<RiddleGate {game} />
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
