package org.acme.dungeon;

/**
 * Thrown by {@link LockService} when the lock-pick jams. This is the error the Trap Corridor's
 * retry loop reacts to (REQ-FUNC-004): repeated jams eventually respawn the player to the fork.
 */
public class LockJammedException extends RuntimeException {

    public LockJammedException(String message) {
        super(message);
    }
}
