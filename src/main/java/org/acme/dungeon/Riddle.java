package org.acme.dungeon;

import java.util.List;
import java.util.Set;

/**
 * One riddle guarding a door. The player must answer it before the workflow will let them through.
 *
 * @param id        stable identifier, used to correlate a posed riddle with its answer
 * @param prompt    the riddle text shown to the player
 * @param canonical the answer as it should be revealed once solved
 * @param accepted  every answer treated as correct, already lowercase. Proximity scoring measures
 *                  distance to the NEAREST of these, so listing near-misses here matters: a riddle
 *                  with one accepted spelling makes an otherwise-right answer read as ice cold
 * @param hints     progressive hints, one per failed attempt. The last is nearly a giveaway - the gate
 *                  exists to teach a workflow primitive, not to end the session
 * @param heat      how much the thermometer should exaggerate closeness (1.0 = linear). Harder riddles
 *                  use a lower value so partial answers read cooler and the player keeps working
 */
public record Riddle(
        String id,
        String prompt,
        String canonical,
        Set<String> accepted,
        List<String> hints,
        double heat) {

    /** The hint for a given attempt number (1-based), or the last one once they run out. */
    public String hintFor(int attempt) {
        if (hints.isEmpty()) {
            return "No hints remain. Trust the wording.";
        }
        int index = Math.min(Math.max(attempt, 1), hints.size()) - 1;
        return hints.get(index);
    }
}
