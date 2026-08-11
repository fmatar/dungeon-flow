package org.acme.dungeon;

/**
 * CloudEvent type names for player moves, and the correlation attribute that binds a move to a
 * single running workflow instance (one player's game session).
 *
 * <p>For the Crawl phase we correlate on the raw workflow instance id (PRD Open Question #1: the
 * simplest option). The listen filters in {@link DungeonWorkflow} use
 * {@code extensionByInstanceId(CORRELATION_ATTR)}, and {@link DungeonResource} stamps that same
 * extension onto every published move. Switching to a {@code playerid} correlation later (Run
 * phase) is a change in this one place plus the resource.
 */
public final class GameEvents {

    private GameEvents() {
    }

    /** CloudEvent extension attribute carrying the target instance id. Must be lowercase (CE spec). */
    public static final String CORRELATION_ATTR = "dungeoninstance";

    /** Player picks a direction at the fork. Data: {"direction":"left"|"right"}. */
    public static final String CHOICE = "game.choice";

    /** Player pulls lever A in the Lever Room. Data: {} */
    public static final String LEVER_A = "game.lever.a";

    /** Player pulls lever B in the Lever Room. Data: {} */
    public static final String LEVER_B = "game.lever.b";
}
