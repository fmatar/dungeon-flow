package org.acme.dungeon;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Maps the complete combined workflow context (direction choice, class, stats)
 * to support advanced data-driven conditional switching in the workflow DSL.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowContext {
    private String direction;
    private String playerClass;
    private PlayerStats stats;

    public WorkflowContext() {}

    public WorkflowContext(String direction, String playerClass, PlayerStats stats) {
        this.direction = direction;
        this.playerClass = playerClass;
        this.stats = stats;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getPlayerClass() {
        return playerClass;
    }

    public void setPlayerClass(String playerClass) {
        this.playerClass = playerClass;
    }

    public PlayerStats getStats() {
        return stats;
    }

    public void setStats(PlayerStats stats) {
        this.stats = stats;
    }

    public boolean isLeft() {
        return "left".equalsIgnoreCase(direction);
    }

    public boolean isRight() {
        return "right".equalsIgnoreCase(direction);
    }

    public boolean isWarrior() {
        return stats != null && stats.strength() >= 12;
    }

    @Override
    public String toString() {
        return "WorkflowContext{dir=" + direction + ",class=" + playerClass + ",stats=" + stats + "}";
    }
}
