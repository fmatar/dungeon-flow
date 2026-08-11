package org.acme.dungeon;

/**
 * The rooms of the dungeon. Each corresponds to one (or a small cluster of) workflow state(s) in
 * {@link DungeonWorkflow}. Kept as an enum so narration and the read-model stay in sync with the map.
 */
public enum Room {
    ENTRANCE,
    FORK,
    LEVER_ROOM,
    TRAP_CORRIDOR,
    TREASURE_ROOM
}
