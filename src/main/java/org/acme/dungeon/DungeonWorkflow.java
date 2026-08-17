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
    StreamEvent.class,
    RiddleState.class,
    RiddleView.class,
    RiddleAnswer.class,
    WorkflowContext.class,
    PlayerStats.class
})
@ApplicationScoped
public class DungeonWorkflow extends Flow {

    /** CNCF standard timeout error type; raised when the fork listen exceeds its torch timeout. */
    static final String TIMEOUT_ERROR = "https://serverlessworkflow.io/spec/1.0.0/errors/timeout";

    @Inject
    GameStore store;

    @Inject
    LockService lockService;

    @Inject
    RiddleService riddles;

    @ConfigProperty(name = "dungeon.riddle.max-attempts", defaultValue = "3")
    int riddleAttempts;

    @ConfigProperty(name = "dungeon.trap.max-attempts", defaultValue = "3")
    int maxAttempts;

    @ConfigProperty(name = "dungeon.fork.torch-timeout", defaultValue = "PT60S")
    String torchTimeout;

    @Override
    public Workflow descriptor() {
        // Capture config into locals so the DSL lambdas don't close over the CDI proxy.
        final int maxTries = this.maxAttempts;
        final int riddleTries = this.riddleAttempts;
        final String torch = this.torchTimeout;
        final GameStore gameStore = this.store;
        final LockService locks = this.lockService;
        final RiddleService riddleService = this.riddles;

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
                        // Unknown directions fall through to the fork again (respawn). Both real
                        // directions go to the SAME gate - the direction travels in the RiddleState
                        // that PoseRiddle emits, so one gate serves both doors.
                        switchCase("WhichWay",
                                caseOf((PlayerMove m) -> m.isLeft(), PlayerMove.class).then("CheckLeftGateType"),
                                caseOf((PlayerMove m) -> m.isRight(), PlayerMove.class).then("PoseRiddleRight"),
                                caseDefault("Fork")),

                        withInstanceId("CheckLeftGateType",
                                (id, in) -> {
                                    PlayerStats stats = gameStore.stats(id);
                                    return (stats != null && stats.strength() >= 12) ? "warrior" : "normal";
                                },
                                Object.class, String.class)
                                .then("LeftGateDecision"),

                        switchCase("LeftGateDecision",
                                caseOf((String type) -> "warrior".equals(type), String.class).then("BashLeftGate"),
                                caseOf((String type) -> "normal".equals(type), String.class).then("PoseRiddleLeft"),
                                caseDefault("PoseRiddleLeft")),

                        withInstanceId("BashLeftGate",
                                (id, in) -> gameStore.enter(id, Narratives.BASH_GATE), Object.class)
                                .then("WaitLevers"),

                        // === Riddle gate: listen + bounded retry + compensation ==============
                        // Every door is gated by a riddle. The shape is deliberately the same as the
                        // Trap Corridor's lock - a listen for the player's answer, a switch on the
                        // graded result, and a compensation when attempts run out - because seeing one
                        // primitive guard two completely different fictions is the lesson.
                        //
                        // Two entry tasks rather than one only because the chosen direction has to be
                        // captured into workflow data; from AwaitAnswer onward there is a single path.
                        withInstanceId("PoseRiddleLeft",
                                (id, in) -> poseGate(gameStore, riddleService, id, "left"),
                                Object.class, RiddleState.class)
                                .then("AwaitAnswer"),

                        withInstanceId("PoseRiddleRight",
                                (id, in) -> poseGate(gameStore, riddleService, id, "right"),
                                Object.class, RiddleState.class)
                                .then("AwaitAnswer"),

                        // The engine parks here, consuming nothing, until the player answers.
                        listen("AwaitAnswer",
                                toOne(consumed(GameEvents.RIDDLE_ANSWER)
                                        .extensionByInstanceId(GameEvents.CORRELATION_ATTR)))
                                .then("GradeAnswer"),

                        // Grading is computation, not routing: it returns how close the answer was and
                        // lets the switches below decide what that means.
                        withInstanceId("GradeAnswer",
                                (id, in) -> gradeGate(gameStore, riddleService, id),
                                Object.class, RiddleState.class)
                                .then("RiddleVerdict"),

                        switchWhenOrElse("RiddleVerdict",
                                (RiddleState s) -> s.solved(), "GateOpens", "RiddleRetryOrPenalty",
                                RiddleState.class),

                        switchWhenOrElse("RiddleRetryOrPenalty",
                                (RiddleState s) -> s.attempt() >= riddleTries,
                                "RiddlePenalty", "AwaitAnswer",
                                RiddleState.class),

                        withInstanceId("RiddlePenalty",
                                (id, in) -> {
                                    gameStore.clearRiddle(id);
                                    return gameStore.enter(id, Narratives.RIDDLE_FAILED);
                                }, Object.class)
                                .then("Fork"),

                        // Solved: forget the gate and walk through the door the player originally chose.
                        withInstanceId("GateOpens",
                                (id, s) -> {
                                    gameStore.clearRiddle(id);
                                    return s;
                                }, RiddleState.class, RiddleState.class)
                                .then("ThroughTheDoor"),

                        switchCase("ThroughTheDoor",
                                caseOf((RiddleState s) -> s.isLeft(), RiddleState.class).then("LeverRoom"),
                                caseOf((RiddleState s) -> s.isRight(), RiddleState.class).then("TrapCorridor"),
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
                                (id, s) -> {
                                    PlayerStats stats = gameStore.stats(id);
                                    boolean picked = (stats != null && stats.dexterity() >= 12) || locks.tryPick();
                                    return gameStore.attempt(id, s.nextAttempt(picked));
                                },
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

    /**
     * Pose the gate's riddle and seed the {@link RiddleState} the workflow will route on. Publishes the
     * riddle to the UI and records the gate view, but decides nothing.
     */
    private static RiddleState poseGate(
            GameStore store, RiddleService riddles, String instanceId, String direction) {
        store.enter(instanceId, Narratives.RIDDLE_GATE);
        Riddle riddle = riddles.pose(direction, store.nextGateNumber(instanceId));
        RiddleState state = RiddleState.pose(direction, riddle.id());
        store.poseRiddle(instanceId, new RiddleView(
                riddle.id(), riddle.prompt(), null, 0, riddles.maxAttempts(), 0.0, false, direction));
        return state;
    }

    /**
     * Grade the answer the player submitted for the gate currently posed. Returns the advanced state so
     * the workflow's switches can decide whether the door opens, re-asks, or gives up - this method
     * never makes that call itself (C-1).
     */
    private static RiddleState gradeGate(
            GameStore store, RiddleService riddles, String instanceId) {
        RiddleView posed = store.riddle(instanceId).orElse(null);
        if (posed == null) {
            // No gate on record - the only way here is a stray answer event, so treat it as a wrong
            // attempt against nothing rather than faulting the instance.
            return RiddleState.pose("left", "unknown").graded(false, 0.0);
        }
        Riddle riddle = Riddles.all().stream()
                .filter(r -> r.id().equals(posed.riddleId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown riddle " + posed.riddleId()));

        String answer = store.pendingAnswer(instanceId);
        RiddleState graded = riddles.grade(
                new RiddleState(posed.direction(), posed.riddleId(), posed.attempt(), false,
                        posed.proximity()),
                riddle, answer);

        boolean solved = graded.solved();
        PlayerStats stats = store.stats(instanceId);
        if (stats != null && stats.intellect() >= 12 && graded.proximity() >= 0.5) {
            solved = true;
        }

        RiddleState finalState = new RiddleState(
                graded.direction(), graded.riddleId(), graded.attempt(), solved, graded.proximity());

        // Hints escalate only after a failure, so the first attempt is unaided.
        String hint = solved ? (graded.solved() ? null : "Mage Insight: Your high Intellect allowed you to solve the riddle with a near-miss!") : riddle.hintFor(finalState.attempt());
        store.gradeRiddle(instanceId, new RiddleView(
                riddle.id(), riddle.prompt(), hint, finalState.attempt(), riddles.maxAttempts(),
                finalState.proximity(), solved, finalState.direction()));
        return finalState;
    }
}
