<script lang="ts">
	import { dur, ease, gsap } from '$lib/motion';
	import type { Temperature } from '$lib/types';

	interface Props {
		/** 0.0–1.0 closeness of the last answer. Sets the column height. */
		proximity: number;
		/** Server-computed band. Sets the colour, so client and API can never disagree on "warm". */
		temperature: Temperature;
		/** Bumps on every graded attempt, so two equally-wrong answers still re-animate. */
		attempt: number;
	}

	let { proximity, temperature, attempt }: Props = $props();

	// Colour comes from the SERVER's band, height from proximity. Re-deriving bands here would put a
	// second source of truth in the UI, and the two would drift the first time a threshold moved.
	const BAND: Record<Temperature, { color: string; label: string }> = {
		FREEZING: { color: 'var(--heat-0)', label: 'freezing' },
		COLD: { color: 'var(--heat-1)', label: 'cold' },
		COOL: { color: 'var(--heat-2)', label: 'cool' },
		WARM: { color: 'var(--heat-3)', label: 'warm' },
		HOT: { color: 'var(--heat-4)', label: 'hot' },
		SCALDING: { color: 'var(--heat-5)', label: 'scalding' },
		SOLVED: { color: 'var(--heat-6)', label: 'solved' }
	};

	const band = $derived(BAND[temperature] ?? BAND.FREEZING);

	// Tube geometry in the SVG's own coordinates.
	const TUBE_TOP = 12;
	const TUBE_BOTTOM = 96;
	const TUBE_HEIGHT = TUBE_BOTTOM - TUBE_TOP;

	let column = $state<SVGRectElement | null>(null);
	let bulb = $state<SVGCircleElement | null>(null);
	let shown = $state(0);

	// ONE authored moment for this component: the column filling. Colour, bulb and the counting
	// readout ride the same timeline so they read as a single physical event rather than three
	// coincidental animations. Nothing here loops — a gauge that pulses forever stops meaning
	// anything, and the map's token already owns the app's one repeating motion.
	$effect(() => {
		void attempt;
		if (!column || !bulb) return;

		const target = Math.max(0, Math.min(1, proximity));
		const height = TUBE_HEIGHT * target;
		const tl = gsap.timeline();

		tl.to(column, {
			attr: { height, y: TUBE_BOTTOM - height },
			fill: band.color,
			duration: dur.slow,
			ease: ease.thermal
		})
			.to(bulb, { fill: band.color, duration: dur.normal, ease: ease.thermal }, 0)
			.to(
				{ v: shown },
				{
					v: target,
					duration: dur.slow,
					ease: ease.thermal,
					onUpdate() {
						shown = this.targets()[0].v as number;
					}
				},
				0
			);

		return () => tl.kill();
	});

	const percent = $derived(Math.round(proximity * 100));
</script>

<!--
  role="meter" carries the reading for assistive tech; the animation is decoration on top of it. The
  title explains the mechanic, matching how TorchTimer annotates itself.
-->
<div
	class="flex items-center gap-3"
	role="meter"
	aria-valuemin={0}
	aria-valuemax={100}
	aria-valuenow={percent}
	aria-valuetext="{band.label}, {percent} percent warm"
	aria-label="How close your last answer was"
	title="How close your last answer was to the accepted one. Wrong answers tell you only this."
>
	<svg viewBox="0 0 34 112" class="h-24 w-8 shrink-0" aria-hidden="true">
		<rect
			x="11"
			y={TUBE_TOP}
			width="12"
			height={TUBE_HEIGHT}
			rx="6"
			class="fill-black/60 stroke-surface-700"
			stroke-width="1"
		/>
		<!-- Mercury: height and y animate together so it grows up out of the bulb. -->
		<rect
			bind:this={column}
			x="13"
			y={TUBE_BOTTOM}
			width="8"
			height="0"
			rx="4"
			fill="var(--heat-0)"
		/>
		<circle bind:this={bulb} cx="17" cy="100" r="9" fill="var(--heat-0)" />
		<circle cx="17" cy="100" r="9" class="fill-none stroke-surface-700" stroke-width="1" />
		{#each [0.25, 0.5, 0.75] as tick (tick)}
			<line
				x1="24"
				x2="28"
				y1={TUBE_BOTTOM - TUBE_HEIGHT * tick}
				y2={TUBE_BOTTOM - TUBE_HEIGHT * tick}
				class="stroke-surface-600"
				stroke-width="1"
			/>
		{/each}
	</svg>

	<div class="leading-tight">
		<div class="text-lg font-bold tracking-tight" style="color: {band.color}">
			{band.label}
		</div>
		<div class="text-xs text-surface-300">
			<span class="tabular-nums">{Math.round(shown * 100)}</span>% warm
		</div>
		<p class="mt-1 max-w-[16ch] text-[0.7rem] leading-snug text-surface-400">
			{#if temperature === 'SOLVED'}
				the door is opening
			{:else if proximity === 0}
				nothing to go on yet
			{:else}
				closeness to the answer
			{/if}
		</p>
	</div>
</div>
