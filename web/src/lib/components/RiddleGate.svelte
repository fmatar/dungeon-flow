<script lang="ts">
	import Thermometer from './Thermometer.svelte';
	import { dur, ease, gsap } from '$lib/motion';
	import type { Game } from '$lib/game.svelte';

	interface Props {
		game: Game;
	}

	let { game }: Props = $props();

	let answer = $state('');
	let panel = $state<HTMLElement | null>(null);
	let input = $state<HTMLInputElement | null>(null);

	const riddle = $derived(game.riddle);
	const submitting = $derived(game.answering);
	const spent = $derived(riddle ? riddle.attempt : 0);
	const left = $derived(riddle ? Math.max(0, riddle.maxAttempts - riddle.attempt) : 0);

	// The gate arriving is one authored moment: the panel resolves out of blur, as if the carving were
	// coming into focus. The riddle text is the reason the panel exists, so it is not animated
	// separately — a second staggered reveal would compete with the thermometer, which is the piece
	// that actually needs the eye on every subsequent attempt.
	$effect(() => {
		const id = riddle?.riddleId;
		if (!id || !panel) return;
		const tl = gsap.timeline();
		tl.fromTo(
			panel,
			{ opacity: 0, y: -10, filter: 'blur(6px)' },
			{ opacity: 1, y: 0, filter: 'blur(0px)', duration: dur.normal, ease: ease.settle }
		);
		input?.focus();
		return () => tl.kill();
	});

	// A wrong answer shakes the field. This is information, not decoration: the thermometer says how
	// close, the shake says not yet — and it fires only on a graded failure.
	$effect(() => {
		if (spent === 0 || riddle?.solved || !input) return;
		const tl = gsap.timeline();
		tl.fromTo(input, { x: -5 }, { x: 0, duration: 0.4, ease: 'elastic.out(1.6,0.35)', clearProps: 'x' });
		return () => tl.kill();
	});

	async function submit(event: SubmitEvent) {
		event.preventDefault();
		const attempt = answer.trim();
		if (!attempt || submitting) return;
		await game.answer(attempt);
		answer = '';
	}
</script>

{#if riddle}
	<section
		bind:this={panel}
		class="rounded border border-primary-900 bg-black/40 p-4"
		aria-labelledby="riddle-heading"
	>
		<div class="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
			<h2 id="riddle-heading" class="text-xs uppercase tracking-widest text-primary-500 text-glow">
				⛬ the door is listening
			</h2>
			<span class="text-[0.7rem] text-surface-400">
				bound for the {riddle.direction} · attempt {Math.min(spent + 1, riddle.maxAttempts)} of {riddle.maxAttempts}
			</span>
		</div>

		<!-- The riddle is the task, so it is the largest text in the panel. -->
		<p class="mt-3 max-w-[62ch] whitespace-pre-line text-base leading-relaxed text-surface-50">
			{riddle.prompt}
		</p>

		<div class="mt-5 flex flex-col gap-5 sm:flex-row sm:items-start">
			<Thermometer
				proximity={riddle.proximity}
				temperature={game.temperature}
				attempt={riddle.attempt}
			/>

			<form class="min-w-0 flex-1" onsubmit={submit}>
				<label class="sr-only" for="riddle-answer">Your answer to the riddle</label>
				<div class="flex gap-2">
					<input
						bind:this={input}
						bind:value={answer}
						id="riddle-answer"
						name="answer"
						type="text"
						autocomplete="off"
						autocapitalize="none"
						spellcheck="false"
						disabled={submitting || riddle.solved}
						aria-describedby="riddle-feedback"
						placeholder="speak your answer…"
						class="input min-w-0 flex-1 border-surface-600 bg-surface-800 text-sm text-surface-50
							caret-primary-400 placeholder:text-surface-300
							focus:border-primary-500 disabled:opacity-60"
					/>
					<button
						type="submit"
						class="btn preset-filled-primary-500 shrink-0"
						disabled={submitting || riddle.solved || !answer.trim()}
					>
						{submitting ? 'weighing…' : 'answer'}
					</button>
				</div>

				<!--
				  One live region for every consequence of an attempt, so a screen reader hears
				  "wrong, warm, two attempts left" as a single update instead of three interruptions.
				-->
				<div id="riddle-feedback" class="mt-2 space-y-1" aria-live="polite">
					{#if riddle.solved}
						<p class="text-sm text-primary-300">Correct. The stone is moving.</p>
					{:else if riddle.hint}
						<p class="text-sm text-amber-glow">Hint: {riddle.hint}</p>
						<p class="text-xs text-surface-400">
							{left} attempt{left === 1 ? '' : 's'} left before the gate turns you back to the fork.
						</p>
					{:else}
						<p class="text-xs text-surface-400">
							No hint yet — the first attempt is unaided. A wrong answer earns one.
						</p>
					{/if}
				</div>
			</form>
		</div>
	</section>
{/if}
