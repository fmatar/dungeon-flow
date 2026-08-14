package org.acme.dungeon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Carries a riddle gate's progress through the workflow data, exactly as {@link LockState} does for
 * the Trap Corridor: the workflow's switches route on this record, so the decision of whether to
 * proceed, re-ask or penalise stays visible in the workflow definition rather than hiding in Java
 * (SRS constraint C-1).
 *
 * @param direction the door the player chose and will pass through once solved
 * @param riddleId  which riddle is posed, so a late answer cannot be graded against a new riddle
 * @param attempt   how many answers have been graded, 1-based after the first
 * @param solved    whether the last graded answer was accepted
 * @param proximity 0.0-1.0 closeness of the last answer to the nearest accepted one. Drives the
 *                  thermometer, and is the only feedback a wrong answer gets
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiddleState(
        String direction,
        String riddleId,
        int attempt,
        boolean solved,
        double proximity) {

    /** A freshly posed gate: nothing graded yet, and the thermometer starts cold. */
    public static RiddleState pose(String direction, String riddleId) {
        return new RiddleState(direction, riddleId, 0, false, 0.0);
    }

    /** The same gate, one attempt later. */
    public RiddleState graded(boolean nowSolved, double nowProximity) {
        return new RiddleState(direction, riddleId, attempt + 1, nowSolved, nowProximity);
    }

    /** Where a solved gate leads. Kept here so the workflow's switch reads as data, not logic. */
    public boolean isLeft() {
        return "left".equalsIgnoreCase(direction);
    }

    public boolean isRight() {
        return "right".equalsIgnoreCase(direction);
    }
}
