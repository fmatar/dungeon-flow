package org.acme.dungeon;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * The one bit of Java that drives the Trap Corridor's failure gameplay: a lock that jams at random.
 * It deliberately contains no room-to-room game rules (C-1) - it only answers "did the pick succeed
 * this time?". The retry/respawn <em>logic</em> around it lives in {@link DungeonWorkflow}.
 */
@ApplicationScoped
public class LockService {

    private static final Logger LOG = LoggerFactory.getLogger(LockService.class);

    public enum Mode {
        /** Succeed with {@code successProbability}. */
        RANDOM,
        /** Always pick the lock (deterministic demos/tests of the happy path). */
        ALWAYS_SUCCEED,
        /** Always jam (deterministic demos/tests of retry exhaustion + respawn). */
        ALWAYS_JAM
    }

    @ConfigProperty(name = "dungeon.lock.success-probability", defaultValue = "0.5")
    double successProbability;

    @ConfigProperty(name = "dungeon.lock.mode", defaultValue = "RANDOM")
    Mode mode;

    private final Random random = new Random();
    private final AtomicInteger totalAttempts = new AtomicInteger();

    /**
     * Attempt to pick the lock once.
     *
     * @return {@code true} if the pick succeeded, {@code false} if it jammed.
     */
    public boolean tryPick() {
        totalAttempts.incrementAndGet();
        boolean picked = switch (mode) {
            case ALWAYS_SUCCEED -> true;
            case ALWAYS_JAM -> false;
            case RANDOM -> random.nextDouble() < successProbability;
        };
        LOG.info("Lock-pick attempt: {} (mode={})", picked ? "CLICK - it opens" : "JAMMED", mode);
        return picked;
    }

    // -- Test hooks: let @QuarkusTest force a deterministic outcome without a profile per case. --

    void forceMode(Mode mode) {
        this.mode = mode;
    }

    Mode mode() {
        return mode;
    }

    /** Total pick attempts since the last {@link #resetAttempts()} - used by tests. */
    int totalAttempts() {
        return totalAttempts.get();
    }

    void resetAttempts() {
        totalAttempts.set(0);
    }
}
