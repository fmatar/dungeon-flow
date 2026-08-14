import { gsap } from 'gsap';

/**
 * Shared GSAP setup. Everything animated in this app goes through here so motion stays consistent
 * and, more importantly, so `prefers-reduced-motion` is honoured in exactly one place rather than
 * being remembered per component.
 */

/** True when the user has asked the OS to reduce motion. */
export function prefersReducedMotion(): boolean {
	return (
		typeof window !== 'undefined' &&
		window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
	);
}

// One global default: with reduced motion, every tween collapses to an instant set. GSAP still runs
// its onComplete/onUpdate callbacks, so component logic that depends on them keeps working.
if (typeof window !== 'undefined' && prefersReducedMotion()) {
	gsap.defaults({ duration: 0, ease: 'none' });
}

/** The house easing set — named so the intent is readable at the call site. */
export const ease = {
	/** Settling into place: room transitions, panels arriving. */
	settle: 'power3.out',
	/** Mechanical, deliberate: levers, gates, the thermometer column. */
	mechanism: 'power2.inOut',
	/** A sharp arrival that overshoots slightly — good for a solved gate. */
	snap: 'back.out(2)',
	/** Heat rising: fast at first, then creeping. */
	thermal: 'power1.out'
} as const;

/** Durations in seconds, kept together so the app's rhythm can be tuned in one place. */
export const dur = {
	instant: 0.12,
	quick: 0.28,
	normal: 0.5,
	slow: 0.9
} as const;

/**
 * A timeline that cleans up after itself. Svelte effects return this directly so a component
 * unmounting mid-animation can never leave a tween writing to a detached node.
 */
export function timeline(vars?: gsap.TimelineVars) {
	return gsap.timeline(vars);
}

export { gsap };
