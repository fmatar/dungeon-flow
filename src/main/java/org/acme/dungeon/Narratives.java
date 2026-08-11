package org.acme.dungeon;

/**
 * All player-facing narrative text, in one place. This is the game's *content*; the game's *rules*
 * (how rooms connect, when to retry, when to respawn) live entirely in {@link DungeonWorkflow}
 * (SRS constraint C-1). Both the workflow and the HTTP layer reference these constants so there is a
 * single source of truth for every room's prose.
 */
public final class Narratives {

    private Narratives() {
    }

    public static final GameView ENTRANCE = new GameView(
            Room.ENTRANCE,
            "You stand at the mouth of the dungeon. Cold air breathes from the dark. A single "
                    + "passage leads inward to a fork.",
            "The game has begun. Head to the fork and choose a direction.",
            false);

    public static final GameView FORK = new GameView(
            Room.FORK,
            "The passage splits. To the LEFT, a room hums with the grind of old machinery. To the "
                    + "RIGHT, a narrow corridor glints with the scars of a hundred failed locks.",
            "Send a 'choice' move with direction \"left\" or \"right\". Don't dawdle - your torch "
                    + "is burning down.",
            false);

    public static final GameView TORCH_OUT = new GameView(
            Room.ENTRANCE,
            "Your torch sputters and dies. In the blackness you feel your way back to the entrance "
                    + "and light a fresh one.",
            "You idled too long at the fork (the event timeout fired). You have respawned at the "
                    + "entrance - choose faster this time.",
            false);

    public static final GameView LEVER_ROOM = new GameView(
            Room.LEVER_ROOM,
            "Two great iron levers, A and B, flank a sealed gate. The mechanism will only release "
                    + "when BOTH have been thrown.",
            "Pull lever A and lever B (in any order). The gate holds until both events arrive - "
                    + "this is a join.",
            false);

    public static final GameView TRAP_CORRIDOR = new GameView(
            Room.TRAP_CORRIDOR,
            "A locked grate bars the corridor. Your fingers find the tumblers... the lock is old "
                    + "and temperamental.",
            "The lock-pick may jam. The engine retries it automatically; on repeated failure you "
                    + "are respawned to the fork.",
            false);

    public static final GameView TRAP_RESPAWN = new GameView(
            Room.FORK,
            "The lock jams solid and a hidden trap flings you backward down the passage. You "
                    + "tumble to a stop... back at the fork.",
            "The lock-pick exhausted its retries, so a compensation returned you to the fork. Try "
                    + "again, or take the other path.",
            false);

    public static final GameView TREASURE_ROOM = new GameView(
            Room.TREASURE_ROOM,
            "The final door swings wide. Gold catches your torchlight - the Treasure Room, and it "
                    + "is all yours. You win!",
            "Victory. The instance reached its terminal state - inspecting it now returns this "
                    + "same view with status COMPLETED.",
            true);
}
