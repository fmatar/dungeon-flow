package org.acme.dungeon;

/**
 * A snapshot of what a player sees for their instance: the current room, its narrative, a hint that
 * ties the moment back to the workflow primitive being exercised, and whether they have won.
 *
 * <p>This is the shape returned by the inspect endpoint and stored in {@link GameStore} as the
 * inspection read-model.
 */
public record GameView(Room room, String narrative, String hint, boolean victory) {
}
