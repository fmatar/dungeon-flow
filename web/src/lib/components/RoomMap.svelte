<script lang="ts">
	import { ROOM_ORDER, ROOM_LABEL, ROOM_GLYPH, roomIndex } from '$lib/rooms';
	import type { Room } from '$lib/types';

	let { current, victory = false }: { current: Room | null; victory?: boolean } = $props();

	const idx = $derived(roomIndex(current));
</script>

<div class="font-mono">
	<div class="mb-2 text-xs uppercase tracking-widest text-surface-400">workflow map</div>
	<div class="flex items-stretch gap-1">
		{#each ROOM_ORDER as room, i (room)}
			{@const active = room === current}
			{@const visited = (idx > i && idx >= 0) || victory}
			<div
				class="flex-1 rounded border px-1 py-3 text-center transition-colors duration-200
				{active
					? 'border-primary-500 bg-primary-500/10 text-primary-400 text-glow'
					: visited
						? 'border-surface-600 text-surface-300'
						: 'border-surface-800 text-surface-600'}"
			>
				<div class="text-2xl leading-none {active ? 'token' : ''}">{ROOM_GLYPH[room]}</div>
				<div class="mt-2 text-[0.62rem] uppercase leading-tight tracking-wide">
					{ROOM_LABEL[room]}
				</div>
				{#if active}
					<div class="mt-1 text-[0.62rem] text-primary-500">◆ here</div>
				{/if}
			</div>
			{#if i < ROOM_ORDER.length - 1}
				<div class="flex items-center text-surface-700">▸</div>
			{/if}
		{/each}
	</div>
	<div class="mt-2 text-[0.65rem] text-surface-500">
		left → lever room → trap · right → trap · unknown / torch-out → respawn
	</div>
</div>
