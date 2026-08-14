package org.acme.dungeon;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One frame pushed to a browser over the SSE stream ({@code GET /api/dungeon/{id}/stream}).
 *
 * <ul>
 *   <li>{@code kind == "state"} - the instance entered a room; carries the new {@link GameView} and
 *       workflow status. This is what moves the token and updates the narrative live.</li>
 *   <li>{@code kind == "attempt"} - the Trap Corridor tried the lock; carries the attempt number and
 *       whether it picked. Lets the UI animate the retry counter as it actually happens.</li>
 *   <li>{@code kind == "riddle"} - a gate posed a riddle, or graded an answer. Carries the
 *       {@link RiddleView}, which is what drives the riddle panel and the thermometer.</li>
 * </ul>
 *
 * Null fields are omitted by the JSON serializer for the kinds that don't use them.
 */
@RegisterForReflection
public record StreamEvent(
        String kind,
        String instanceId,
        String status,
        GameView view,
        Integer attempt,
        Boolean picked,
        RiddleView riddle) {

    public static StreamEvent state(String instanceId, String status, GameView view) {
        return new StreamEvent("state", instanceId, status, view, null, null, null);
    }

    public static StreamEvent attempt(String instanceId, int attempt, boolean picked) {
        return new StreamEvent("attempt", instanceId, null, null, attempt, picked, null);
    }

    /** A gate posing a riddle or reporting how warm an answer was. */
    public static StreamEvent riddle(String instanceId, RiddleView riddle) {
        return new StreamEvent("riddle", instanceId, null, null, null, null, riddle);
    }
}
