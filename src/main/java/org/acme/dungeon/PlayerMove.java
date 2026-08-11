package org.acme.dungeon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The data payload of a {@code game.choice} CloudEvent: which way the player turns at the fork.
 * Levers carry no payload (an empty object), so they don't need a type.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlayerMove(String direction) {

    public boolean isLeft() {
        return "left".equalsIgnoreCase(direction);
    }

    public boolean isRight() {
        return "right".equalsIgnoreCase(direction);
    }
}
