package org.acme.dungeon;

/**
 * One frame pushed to a browser over the SSE stream ({@code GET /dungeon/{id}/stream}).
 *
 * <ul>
 *   <li>{@code kind == "state"} - the instance entered a room; carries the new {@link GameView} and
 *       workflow status. This is what moves the token and updates the narrative live.</li>
 *   <li>{@code kind == "attempt"} - the Trap Corridor tried the lock; carries the attempt number and
 *       whether it picked. Lets the UI animate the retry counter as it actually happens.</li>
 * </ul>
 *
 * Null fields are simply omitted by the JSON serializer for the kind that doesn't use them.
 */
public record StreamEvent(
        String kind,
        String instanceId,
        String status,
        GameView view,
        Integer attempt,
        Boolean picked) {

    public static StreamEvent state(String instanceId, String status, GameView view) {
        return new StreamEvent("state", instanceId, status, view, null, null);
    }

    public static StreamEvent attempt(String instanceId, int attempt, boolean picked) {
        return new StreamEvent("attempt", instanceId, null, null, attempt, picked);
    }
}
