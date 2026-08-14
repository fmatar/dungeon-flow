package org.acme.dungeon;

import java.util.List;
import java.util.Set;

/**
 * The riddle bank. Deliberately hard: these are cryptic and multi-clause, because a gate that is
 * trivially passed teaches nothing about the workflow primitive holding it shut.
 *
 * <p>Each riddle's {@code accepted} set is generous about spelling and article use, since the
 * thermometer measures distance to the nearest accepted answer - a correct idea rejected on a
 * technicality reads as ice cold and feels broken rather than hard.
 */
public final class Riddles {

    private Riddles() {
    }

    /** Guards the LEFT door, toward the Lever Room. Themed on things that wait and join. */
    public static final List<Riddle> LEFT = List.of(
            new Riddle(
                    "left-echo",
                    """
                    I am spoken once but heard again, born only where a wall says no.
                    Shout in the open field and I die unborn; shout in a cavern and I outlive
                    the shout. What am I?""",
                    "an echo",
                    Set.of("echo", "an echo", "the echo", "echoes", "reverberation"),
                    List.of(
                            "I need a boundary to exist at all.",
                            "I am a copy that arrives late.",
                            "You hear me after you stop speaking — in caves, most of all."),
                    0.85),
            new Riddle(
                    "left-tomorrow",
                    """
                    I am always approaching yet never arrive. Every plan is laid in me,
                    no work is ever done in me, and the moment you reach me I am gone
                    and wearing another name. What am I?""",
                    "tomorrow",
                    Set.of("tomorrow", "the future", "future", "tommorow", "tomorow"),
                    List.of(
                            "I am a time, not a place.",
                            "When I arrive, I am renamed 'today'.",
                            "Jam yesterday and jam tomorrow — but never jam today."),
                    0.8),
            new Riddle(
                    "left-silence",
                    """
                    The more of me you take, the more you leave behind; I grow in libraries
                    and die at parties; I am the only answer that a question cannot argue with.
                    What am I?""",
                    "silence",
                    Set.of("silence", "quiet", "quietness", "stillness", "nothing", "silense"),
                    List.of(
                            "I am destroyed the instant you describe me out loud.",
                            "Libraries protect me. Parties end me.",
                            "Break me by speaking."),
                    0.75));

    /** Guards the RIGHT door, toward the Trap Corridor. Themed on locks, keys and failure. */
    public static final List<Riddle> RIGHT = List.of(
            new Riddle(
                    "right-keyboard",
                    """
                    I have keys but open no doors, space but no room, and you can enter
                    but never go inside. I am struck a thousand times a day and never complain.
                    What am I?""",
                    "a keyboard",
                    Set.of("keyboard", "a keyboard", "the keyboard", "keybord", "computer keyboard"),
                    List.of(
                            "My keys are pressed, not turned.",
                            "One of my keys is named after a room I do not have.",
                            "You are almost certainly touching me right now."),
                    0.9),
            new Riddle(
                    "right-shadow",
                    """
                    I follow without being asked and lead without knowing where. I am tallest
                    at dusk, absent at noon in the desert, and I vanish the instant you
                    surround me with light. What am I?""",
                    "a shadow",
                    Set.of("shadow", "a shadow", "the shadow", "my shadow", "shaddow", "shadows"),
                    List.of(
                            "I am a shape made of absence.",
                            "Two lamps can erase me; one lamp creates me.",
                            "I am cast."),
                    0.85),
            new Riddle(
                    "right-lock",
                    """
                    I am the only thing that grows stronger the more you fail at me, and weaker
                    the moment someone succeeds once. Three wrong turns of the wrist and I am
                    still exactly as shut as I was. What am I?""",
                    "a lock",
                    Set.of("lock", "a lock", "the lock", "padlock", "a padlock", "loch"),
                    List.of(
                            "I am the thing this corridor is famous for.",
                            "A key ends my career.",
                            "You have been picking me since you arrived."),
                    0.8));

    /**
     * Pick a riddle for a direction, rotating deterministically by how many gates the player has
     * already faced so a session sees variety and a demo is reproducible.
     */
    public static Riddle forDirection(String direction, int gateNumber) {
        List<Riddle> bank = "right".equalsIgnoreCase(direction) ? RIGHT : LEFT;
        int index = Math.floorMod(gateNumber, bank.size());
        return bank.get(index);
    }

    /** Every riddle, for tests and for the hint-coverage check. */
    public static List<Riddle> all() {
        return List.copyOf(
                java.util.stream.Stream.concat(LEFT.stream(), RIGHT.stream()).toList());
    }
}
