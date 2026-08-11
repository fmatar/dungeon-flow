<script lang="ts">
	import { listGames, startGame, choose, pullLever, cleanup } from '$lib/api';
	import { ROOM_ORDER, ROOM_LABEL, roomIndex } from '$lib/rooms';
	import type { StateResponse } from '$lib/types';

	let racers = $state<StateResponse[]>([]);
	let error = $state<string | null>(null);
	let busy = $state(false);

	// Poll the list endpoint - the facilitator's live scoreboard. Cheap and matches the
	// backend's list operation (REQ-FUNC-008/012).
	$effect(() => {
		let alive = true;
		const tick = async () => {
			try {
				const r = await listGames();
				if (alive) racers = r;
			} catch (e) {
				if (alive) error = String(e);
			}
		};
		tick();
		const iv = setInterval(tick, 1000);
		return () => {
			alive = false;
			clearInterval(iv);
		};
	});

	const winner = $derived(racers.find((r) => r.status === 'COMPLETED'));

	async function addRacer() {
		busy = true;
		try {
			await startGame();
		} catch (e) {
			error = String(e);
		} finally {
			busy = false;
		}
	}

	async function remove(id: string) {
		try {
			await cleanup(id);
			racers = racers.filter((r) => r.instanceId !== id);
		} catch (e) {
			error = String(e);
		}
	}
</script>

<div class="space-y-4">
	<div class="flex flex-wrap items-center justify-between gap-3">
		<div>
			<h1 class="font-mono text-xl text-primary-400 text-glow">race</h1>
			<p class="text-sm text-surface-400">
				Every active instance, live. First to the Treasure Room wins. Each runs on the same
				container, fully isolated.
			</p>
		</div>
		<button class="btn preset-filled-primary-500" onclick={addRacer} disabled={busy}>
			+ add racer
		</button>
	</div>

	{#if winner}
		<div
			class="rounded border border-primary-500 bg-primary-500/10 p-4 font-mono text-primary-300 text-glow"
		>
			✦ winner: {winner.instanceId.slice(0, 16)}…
		</div>
	{/if}

	{#if error}
		<p class="font-mono text-sm text-error-400">! {error}</p>
	{/if}

	{#if racers.length === 0}
		<div
			class="rounded border border-dashed border-surface-700 p-8 text-center font-mono text-surface-500"
		>
			no racers yet — add one, or start games from the play screen
		</div>
	{:else}
		<div class="grid gap-3 sm:grid-cols-2">
			{#each racers as r (r.instanceId)}
				{@const idx = roomIndex(r.view?.room)}
				{@const done = r.status === 'COMPLETED'}
				<div
					class="card border p-4 {done
						? 'border-primary-500 bg-primary-500/5'
						: 'border-surface-800 bg-surface-950/60'}"
				>
					<div class="flex items-center justify-between font-mono text-xs">
						<span class="text-surface-400">{r.instanceId.slice(0, 14)}…</span>
						<span
							class="badge {done
								? 'preset-filled-primary-500'
								: 'preset-tonal-surface'} text-[0.65rem]">{r.status}</span
						>
					</div>

					<!-- compact 5-room track -->
					<div class="mt-3 flex gap-1">
						{#each ROOM_ORDER as room, i (room)}
							{@const active = r.view?.room === room}
							{@const passed = idx > i || done}
							<div
								class="h-2 flex-1 rounded-sm {active
									? 'bg-primary-500 token'
									: passed
										? 'bg-primary-500/40'
										: 'bg-surface-800'}"
								title={ROOM_LABEL[room]}
							></div>
						{/each}
					</div>
					<div class="mt-1 font-mono text-[0.7rem] text-surface-400">
						{r.view ? ROOM_LABEL[r.view.room] : '…'}{done ? ' · finished' : ''}
					</div>

					<!-- facilitator quick-nudge controls -->
					<div class="mt-3 flex items-center gap-2 font-mono text-xs">
						{#if done}
							<span class="text-primary-400 text-glow">✦ cleared</span>
						{:else if r.view?.room === 'FORK'}
							<button class="btn btn-sm preset-tonal-surface" onclick={() => choose(r.instanceId, 'left')}
								>L</button
							>
							<button
								class="btn btn-sm preset-tonal-surface"
								onclick={() => choose(r.instanceId, 'right')}>R</button
							>
						{:else if r.view?.room === 'LEVER_ROOM'}
							<button
								class="btn btn-sm preset-tonal-surface"
								onclick={() => pullLever(r.instanceId, 'a')}>A</button
							>
							<button
								class="btn btn-sm preset-tonal-surface"
								onclick={() => pullLever(r.instanceId, 'b')}>B</button
							>
						{:else}
							<span class="text-surface-500">running…</span>
						{/if}
						<button
							class="btn btn-sm preset-tonal-surface ml-auto text-surface-500"
							onclick={() => remove(r.instanceId)}>✕</button
						>
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>
