package org.acme.dungeon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Carries the Trap Corridor's retry progress through the workflow data as the pick-lock loop runs:
 * how many attempts have been made, and whether the last one succeeded. Modelling the counter in
 * workflow data (rather than in the LockService) keeps the retry logic visible in the workflow and
 * the diagram (C-1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LockState(int attempt, boolean picked) {

    public static LockState start() {
        return new LockState(0, false);
    }

    public LockState nextAttempt(boolean pickedThisTime) {
        return new LockState(attempt + 1, pickedThisTime);
    }
}
