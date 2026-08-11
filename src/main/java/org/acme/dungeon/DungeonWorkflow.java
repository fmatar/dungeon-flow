package org.acme.dungeon;

import static io.quarkiverse.flow.dsl.FlowDSL.caseDefault;
import static io.quarkiverse.flow.dsl.FlowDSL.caseOf;
import static io.quarkiverse.flow.dsl.FlowDSL.consumed;
import static io.quarkiverse.flow.dsl.FlowDSL.function;
import static io.quarkiverse.flow.dsl.FlowDSL.listen;
import static io.quarkiverse.flow.dsl.FlowDSL.switchCase;
import static io.quarkiverse.flow.dsl.FlowDSL.switchWhenOrElse;
import static io.quarkiverse.flow.dsl.FlowDSL.to;
import static io.quarkiverse.flow.dsl.FlowDSL.toOne;
import static io.quarkiverse.flow.dsl.FlowDSL.tryCatch;
import static io.quarkiverse.flow.dsl.FlowDSL.withInstanceId;
import static io.quarkiverse.flow.dsl.FlowWorkflowBuilder.workflow;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.FlowDirectiveEnum;
import io.serverlessworkflow.api.types.Workflow;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The dungeon <em>is</em> this workflow. Rooms are states, doors are transitions, and player moves
 * are CloudEvents. Nothing about how the rooms connect lives in Java - it is all declared here
 * (SRS constraint C-1). The Java beans it calls ({@link GameStore}, {@link LockService}) only
 * observe or roll dice; they never decide where the player goes next.
 *
 * <p>Map:
 *
 * <pre>
 *   Entrance --> Fork --(left)--> Lever Room --(join A&B)--> Trap Corridor --(pick)--> Treasure Room (END)
 *                 |  \--(right)-----------------------------> Trap Corridor
 *                 |  \--(unknown / torch timeout)-----------> respawn
 *                 ^                                                 |
 *                 \------------------- respawn on retry exhaustion -/
 * </pre>
 *
 * Primitives exercised: event wait ({@code listen}), data switch ({@code switch}), multi-event join
 * ({@code listen.all}), bounded retry + compensation (explicit loop + respawn), and an event
 * timeout ({@code listen.timeout} caught by {@code try}).
 */
@RegisterForReflection(targets = {
    LockState.class,
    PlayerMove.class,
    GameView.class,
    Room.class,
    StreamEvent.class
})
@ApplicationScoped
public class DungeonWorkflow extends Flow {

    /** CNCF standard timeout error type; raised when the fork listen exceeds its torch timeout. */
    static final String TIMEOUT_ERROR = "https://serverlessworkflow.io/spec/1.0.0/errors/timeout";

    @Inject
    GameStore store;

    @Inject
    LockService lockService;

    @ConfigProperty(name = "dungeon.trap.max-attempts", defaultValue = "3")
    int maxAttempts;

    @ConfigProperty(name = "dungeon.fork.torch-timeout", defaultValue = "PT60S")
    String torchTimeout;

    @Override
    public Workflow descriptor() {
        // Capture config into locals so the DSL lambdas don't close over the CDI proxy.
        final int maxTries = this.maxAttempts;
        final String torch = this.torchTimeout;
        final GameStore gameStore = this.store;
        final LockService locks = this.lockService;

        return workflow("dungeon-flow")

                // === Entrance ============================================================
                // REQ-FUNC-001: the starting room. Records the Entrance view, then walks to the fork.
                .tasks(
                        withInstanceId("Entrance",
                                (id, in) -> gameStore.enter(id, Narratives.ENTRANCE), Object.class)
                                .then("Fork"),

                        // === Fork: choice + torch timeout ==================================
                        // REQ-FUNC-002 (switch) and REQ-FUNC-005 (timeout). The listen is wrapped in a
                        // try so that when the torch timeout fires we can respawn instead of faulting.
                        withInstanceId("Fork",
                                (id, in) -> gameStore.enter(id, Narratives.FORK), Object.class)
                                .then("ForkWait"),

                        tryCatch("ForkWait", t -> t
                                .tryCatch(inner -> listen("WaitChoice",
                                        toOne(consumed(GameEvents.CHOICE)
                                                .extensionByInstanceId(GameEvents.CORRELATION_ATTR)))
                                        .timeout(torch)
                                        .accept(inner))
                                .catchType(TIMEOUT_ERROR,
                                        withInstanceId("TorchOut",
                                                (id, in) -> gameStore.enter(id, Narratives.TORCH_OUT),
                                                Object.class)
                                                .then("Entrance"))),

                        // Route on the choice payload (output of the successful listen).
                        // Unknown directions fall through to the fork again (respawn).
                        switchCase("WhichWay",
                                caseOf((PlayerMove m) -> m.isLeft(), PlayerMove.class).then("LeverRoom"),
                                caseOf((PlayerMove m) -> m.isRight(), PlayerMove.class).then("TrapCorridor"),
                                caseDefault("Fork")),

                        // === Lever Room: two-lever join ====================================
                        // REQ-FUNC-003: advance only after BOTH lever events arrive, in any order.
                        withInstanceId("LeverRoom",
                                (id, in) -> gameStore.enter(id, Narratives.LEVER_ROOM), Object.class)
                                .then("WaitLevers"),

                        listen("WaitLevers", to().all(
                                consumed(GameEvents.LEVER_A).extensionByInstanceId(GameEvents.CORRELATION_ATTR),
                                consumed(GameEvents.LEVER_B).extensionByInstanceId(GameEvents.CORRELATION_ATTR)))
                                .then("TrapCorridor"),

                        // === Trap Corridor: bounded retry + respawn ========================
                        // REQ-FUNC-004: retry the pick up to maxTries; on exhaustion respawn to the fork.
                        withInstanceId("TrapCorridor",
                                (id, in) -> {
                                    gameStore.enter(id, Narratives.TRAP_CORRIDOR);
                                    return LockState.start();
                                }, Object.class)
                                .then("PickLock"),

                        withInstanceId("PickLock",
                                (id, s) -> gameStore.attempt(id, s.nextAttempt(locks.tryPick())),
                                LockState.class, LockState.class)
                                .then("Picked"),

                        switchWhenOrElse("Picked",
                                (LockState s) -> s.picked(), "TreasureRoom", "RetryOrRespawn",
                                LockState.class),

                        switchWhenOrElse("RetryOrRespawn",
                                (LockState s) -> s.attempt() >= maxTries, "TrapRespawn", "PickLock",
                                LockState.class),

                        withInstanceId("TrapRespawn",
                                (id, in) -> gameStore.enter(id, Narratives.TRAP_RESPAWN), Object.class)
                                .then("Fork"),

                        // === Treasure Room: victory + completion ===========================
                        // REQ-FUNC-006: deliver the victory view and end the instance.
                        withInstanceId("TreasureRoom",
                                (id, in) -> gameStore.enter(id, Narratives.TREASURE_ROOM), Object.class)
                                .then(FlowDirectiveEnum.END))
                .build();
    }
}
