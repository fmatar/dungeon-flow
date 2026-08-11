<script lang="ts">
	// A phosphor countdown ring for the fork's torch timeout. Restarts whenever `resetKey` changes
	// (i.e. each time the instance re-enters the fork). Purely cosmetic - the real timeout lives in
	// the workflow; this just visualizes it.
	let {
		seconds,
		resetKey,
		active
	}: { seconds: number; resetKey: number; active: boolean } = $props();

	let remaining = $state(0);

	$effect(() => {
		// Read props so the effect re-runs when the timer should restart.
		const total = seconds;
		const key = resetKey;
		const on = active;
		void key;
		remaining = total;
		if (!on) return;
		const started = Date.now();
		const iv = setInterval(() => {
			const elapsed = (Date.now() - started) / 1000;
			remaining = Math.max(0, total - elapsed);
			if (remaining <= 0) clearInterval(iv);
		}, 100);
		return () => clearInterval(iv);
	});

	const pct = $derived(seconds > 0 ? remaining / seconds : 0);
	const R = 34;
	const C = 2 * Math.PI * R;
	const dash = $derived(C * pct);
	const danger = $derived(pct < 0.25);
</script>

{#if active}
	<div class="relative h-24 w-24 shrink-0" title="Idle past this and the torch goes out (event timeout)">
		<svg viewBox="0 0 80 80" class="h-24 w-24 -rotate-90">
			<circle cx="40" cy="40" r={R} fill="none" stroke-width="6" class="stroke-surface-800" />
			<circle
				cx="40"
				cy="40"
				r={R}
				fill="none"
				stroke-width="6"
				stroke-linecap="round"
				class={danger ? 'stroke-error-500' : 'stroke-primary-500'}
				stroke-dasharray="{dash} {C}"
				style="transition: stroke-dasharray 0.12s linear"
			/>
		</svg>
		<div class="absolute inset-0 grid place-items-center">
			<div class="text-center leading-none">
				<div class="text-xl {danger ? 'text-amber-glow' : 'text-glow text-primary-400'}">
					{Math.ceil(remaining)}
				</div>
				<div class="mt-0.5 text-[0.55rem] uppercase tracking-widest text-surface-400">torch</div>
			</div>
		</div>
	</div>
{/if}
