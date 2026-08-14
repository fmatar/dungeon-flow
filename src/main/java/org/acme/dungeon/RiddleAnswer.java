package org.acme.dungeon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The body of {@code POST /api/dungeon/{id}/riddle}: one attempt at the gate's riddle.
 *
 * <p>The answer is recorded in {@link GameStore} before the {@code game.riddle.answer} CloudEvent is
 * published, so the event itself is a pure trigger — exactly like the levers, which carry no payload.
 * That keeps the workflow independent of event-payload shapes, which the engine wraps differently
 * depending on how a {@code listen} is composed.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiddleAnswer(String answer) {
}
