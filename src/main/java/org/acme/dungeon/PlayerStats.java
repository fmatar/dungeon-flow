package org.acme.dungeon;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Carries the player's core attributes (Strength, Dexterity, Intellect) used for
 * dynamic workflow path routing and difficulty modulation.
 */
@RegisterForReflection
public class PlayerStats {
    private int strength;
    private int dexterity;
    private int intellect;

    public PlayerStats() {}

    public PlayerStats(int strength, int dexterity, int intellect) {
        this.strength = strength;
        this.dexterity = dexterity;
        this.intellect = intellect;
    }

    public static PlayerStats of(String playerClass) {
        if ("warrior".equalsIgnoreCase(playerClass)) {
            return new PlayerStats(18, 10, 8);
        } else if ("rogue".equalsIgnoreCase(playerClass)) {
            return new PlayerStats(10, 18, 8);
        } else if ("mage".equalsIgnoreCase(playerClass)) {
            return new PlayerStats(8, 10, 18);
        } else {
            return new PlayerStats(10, 10, 10);
        }
    }

    public int getStrength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = strength;
    }

    public int getDexterity() {
        return dexterity;
    }

    public void setDexterity(int dexterity) {
        this.dexterity = dexterity;
    }

    public int getIntellect() {
        return intellect;
    }

    public void setIntellect(int intellect) {
        this.intellect = intellect;
    }

    // Keep compatibility for stats.strength() record-like calls
    public int strength() {
        return strength;
    }

    public int dexterity() {
        return dexterity;
    }

    public int intellect() {
        return intellect;
    }

    @Override
    public String toString() {
        return "PlayerStats{str=" + strength + ",dex=" + dexterity + ",int=" + intellect + "}";
    }
}
