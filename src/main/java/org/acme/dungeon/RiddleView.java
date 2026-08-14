package org.acme.dungeon;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the player is allowed to know about the gate currently holding them: the riddle, the hint they
 * have earned, how many attempts remain, and how warm their last answer was.
 *
 * <p>Deliberately omits the answer. The client is the audience's machine during a demo, and an answer
 * in the network tab is an answer on the projector.
 *
 * @param riddleId    correlates a posed riddle with its grading
 * @param prompt      the riddle text
 * @param hint        escalating hint, revealed only after a failed attempt ({@code null} before)
 * @param attempt     graded attempts so far
 * @param maxAttempts attempts before the gate gives up and the workflow compensates
 * @param proximity   0.0-1.0 closeness of the last answer — the thermometer's value
 * @param solved      whether the gate has opened
 * @param direction   which door this gate guards, so the UI can name it
 */
@RegisterForReflection
public record RiddleView(
        String riddleId,
        String prompt,
        String hint,
        int attempt,
        int maxAttempts,
        double proximity,
        boolean solved,
        String direction) {

    /** Attempts left before the compensation fires. Never negative. */
    public int remaining() {
        return Math.max(0, maxAttempts - attempt);
    }

    /**
     * A coarse band for the thermometer's label and colour. Kept server-side so the UI, the API and
     * any future client agree on what "warm" means.
     */
    public String temperature() {
        if (solved) {
            return "SOLVED";
        }
        if (proximity >= 0.75) {
            return "SCALDING";
        }
        if (proximity >= 0.55) {
            return "HOT";
        }
        if (proximity >= 0.40) {
            return "WARM";
        }
        if (proximity >= 0.25) {
            return "COOL";
        }
        if (proximity > 0.0) {
            return "COLD";
        }
        return "FREEZING";
    }
}
