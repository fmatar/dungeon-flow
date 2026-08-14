package org.acme.dungeon;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import jakarta.inject.Inject;

/**
 * Workflow-level behaviour, driven by publishing correlated CloudEvents directly into the engine
 * (no HTTP). Each test traces to a REQ-FUNC acceptance criterion in the SRS.
 */
@QuarkusTest
class DungeonWorkflowTest {

    @Inject
    DungeonWorkflow dungeon;

    @Inject
    GamePublisher player;

    @Inject
    GameStore store;

    @Inject
    LockService lockService;

    @BeforeEach
    void reset() {
        lockService.forceMode(LockService.Mode.RANDOM);
        lockService.resetAttempts();
    }

    @AfterEach
    void stopAllInstances() {
        // Idle instances (and ALWAYS_JAM ones that respawn) loop on the torch forever by design.
        // Cancel and forget every instance after each test so they don't accumulate and starve the
        // shared engine for later tests in the suite.
        store.all().forEach((id, instance) -> {
            try {
                instance.cancel();
            } catch (RuntimeException ignored) {
                // already terminal - nothing to cancel
            }
        });
        store.all().keySet().forEach(store::remove);
    }

    private WorkflowInstance startAndArm() {
        WorkflowInstance instance = dungeon.instance(Map.of());
        store.register(instance);
        instance.start();
        await().atMost(ofSeconds(5)).until(() -> instance.status() == WorkflowStatus.WAITING);
        return instance;
    }

    private void awaitStatus(WorkflowInstance instance, WorkflowStatus status) {
        await().atMost(ofSeconds(10)).until(() -> instance.status() == status);
    }

    private void awaitRoom(WorkflowInstance instance, Room room) {
        await().atMost(ofSeconds(10))
                .until(() -> store.view(instance.id()).map(GameView::room).orElse(null) == room);
    }

    // === REQ-FUNC-001: start ================================================================

    @Test
    @DisplayName("REQ-FUNC-001: start creates an isolated instance parked at the fork")
    void start_parks_at_fork() {
        WorkflowInstance instance = startAndArm();

        assertThat(instance.id()).isNotBlank();
        assertThat(store.view(instance.id())).map(GameView::room).hasValue(Room.FORK);
        // The opening trail is Entrance then Fork - the map's first two states.
        assertThat(store.trail(instance.id()))
                .extracting(GameView::room)
                .startsWith(Room.ENTRANCE, Room.FORK);
    }

    // === REQ-FUNC-002: choice switch ========================================================

    @Test
    @DisplayName("REQ-FUNC-002: choosing right routes to the Trap Corridor")
    void choice_right_routes_to_trap() {
        lockService.forceMode(LockService.Mode.ALWAYS_JAM); // stop at trap so we can observe the room
        WorkflowInstance instance = startAndArm();

        player.choiceAndSolve(instance.id(), "right");

        // With ALWAYS_JAM the Trap Corridor is transient (enter -> jam x3 -> respawn in ~1ms), so
        // polling the *latest* room races and usually misses it. The trail records every room
        // entered, including transient ones - await the trap appearing there instead.
        await().atMost(ofSeconds(10)).until(() ->
                store.trail(instance.id()).stream().anyMatch(v -> v.room() == Room.TRAP_CORRIDOR));
        assertThat(store.trail(instance.id())).extracting(GameView::room)
                .contains(Room.TRAP_CORRIDOR)
                .doesNotContain(Room.LEVER_ROOM);
    }

    @Test
    @DisplayName("REQ-FUNC-002: choosing left routes to the Lever Room")
    void choice_left_routes_to_lever_room() {
        WorkflowInstance instance = startAndArm();

        player.choiceAndSolve(instance.id(), "left");

        awaitRoom(instance, Room.LEVER_ROOM);
    }

    // === REQ-FUNC-003: two-lever join =======================================================


    // === Riddle gates: listen + bounded retry + compensation ================================

    @Test
    @DisplayName("a riddle gate holds every door: choosing a direction poses one and waits")
    void gate_poses_riddle_and_waits() {
        WorkflowInstance instance = startAndArm();

        player.choice(instance.id(), "left");
        RiddleView posed = player.awaitGate(instance.id());

        assertThat(posed.prompt()).isNotBlank();
        assertThat(posed.direction()).isEqualTo("left");
        assertThat(posed.attempt()).isZero();
        // No hint before a failure - the first attempt is unaided.
        assertThat(posed.hint()).isNull();
        assertThat(posed.solved()).isFalse();
        // The engine is parked on the answer listen, not progressing on its own.
        awaitStatus(instance, WorkflowStatus.WAITING);
        assertThat(store.view(instance.id())).map(GameView::room).hasValue(Room.FORK);
    }

    @Test
    @DisplayName("a wrong answer keeps the door shut, earns a hint, and reports how close it was")
    void gate_wrong_answer_holds_and_warms() {
        WorkflowInstance instance = startAndArm();
        player.choice(instance.id(), "left");
        player.awaitGate(instance.id());

        player.answerWhenArmed(instance.id(), "a bucket of custard");
        player.awaitGraded(instance.id(), 1);
        RiddleView graded = store.riddle(instance.id()).orElseThrow();

        assertThat(graded.solved()).isFalse();
        assertThat(graded.hint()).isNotBlank();
        assertThat(graded.proximity()).isBetween(0.0, 1.0);
        assertThat(graded.remaining()).isEqualTo(graded.maxAttempts() - 1);
        // Still gated: it must NOT have leaked into the Lever Room.
        assertThat(store.view(instance.id())).map(GameView::room).hasValue(Room.FORK);
    }

    @Test
    @DisplayName("a near-miss reads warmer than a nonsense answer (the thermometer is real)")
    void gate_proximity_orders_answers() {
        WorkflowInstance instance = startAndArm();
        player.choice(instance.id(), "left");
        RiddleView posed = player.awaitGate(instance.id());
        String canonical = player.canonicalAnswerFor(posed.riddleId());

        // A typo of the right answer, versus something unrelated.
        String nearMiss = canonical.substring(0, Math.max(1, canonical.length() - 1)) + "x";
        player.answerWhenArmed(instance.id(), "concrete mixer");
        player.awaitGraded(instance.id(), 1);
        double cold = store.riddle(instance.id()).orElseThrow().proximity();

        player.answerWhenArmed(instance.id(), nearMiss);
        player.awaitGraded(instance.id(), 2);
        double warm = store.riddle(instance.id()).orElseThrow().proximity();

        assertThat(warm).isGreaterThan(cold);
    }

    @Test
    @DisplayName("the correct answer opens the door and routes to the chosen room")
    void gate_correct_answer_opens_chosen_door() {
        WorkflowInstance instance = startAndArm();

        player.choiceAndSolve(instance.id(), "left");

        awaitRoom(instance, Room.LEVER_ROOM);
        // The gate is forgotten once passed, so a stale riddle cannot be answered twice.
        assertThat(store.riddle(instance.id())).isEmpty();
    }

    @Test
    @DisplayName("the right door's gate leads to the Trap Corridor, not the Lever Room")
    void gate_routes_right_door_to_trap() {
        lockService.forceMode(LockService.Mode.ALWAYS_JAM);
        WorkflowInstance instance = startAndArm();

        player.choiceAndSolve(instance.id(), "right");

        // ALWAYS_JAM respawns quickly, so assert on where it HAS been, not where it is now.
        await().atMost(ofSeconds(10)).until(() -> store.trail(instance.id()).stream()
                .anyMatch(v -> v.room() == Room.TRAP_CORRIDOR));
        assertThat(store.trail(instance.id()))
                .extracting(GameView::room)
                .doesNotContain(Room.LEVER_ROOM);
    }

    @Test
    @DisplayName("exhausting the gate's attempts compensates back to the fork without faulting")
    void gate_exhausts_attempts_then_compensates() {
        WorkflowInstance instance = startAndArm();
        player.choice(instance.id(), "left");
        RiddleView posed = player.awaitGate(instance.id());
        int max = posed.maxAttempts();

        for (int i = 1; i <= max; i++) {
            player.answerWhenArmed(instance.id(), "definitely not the answer " + i);
            player.awaitGraded(instance.id(), i);
        }

        // The compensation clears the gate and returns the player to the fork, still WAITING.
        await().atMost(ofSeconds(10)).until(() -> store.riddle(instance.id()).isEmpty());
        assertThat(store.trail(instance.id()))
                .extracting(GameView::narrative)
                .anySatisfy(n -> assertThat(n).contains("sets you back at the fork"));
        awaitStatus(instance, WorkflowStatus.WAITING);
        assertThat(instance.status()).isNotEqualTo(WorkflowStatus.FAULTED);
    }

    @Test
    @DisplayName("after a gate compensation the player can choose again and get a fresh gate")
    void gate_is_playable_after_compensation() {
        WorkflowInstance instance = startAndArm();
        player.choice(instance.id(), "left");
        RiddleView first = player.awaitGate(instance.id());

        for (int i = 1; i <= first.maxAttempts(); i++) {
            player.answerWhenArmed(instance.id(), "wrong " + i);
            player.awaitGraded(instance.id(), i);
        }
        await().atMost(ofSeconds(10)).until(() -> store.riddle(instance.id()).isEmpty());

        // Choose again: a new gate must be posed, and solving it must let us through.
        player.choiceAndSolve(instance.id(), "left");
        awaitRoom(instance, Room.LEVER_ROOM);
    }

    @Test
    @DisplayName("REQ-FUNC-003: the gate holds on a single lever")
    void join_holds_on_single_lever() {
        WorkflowInstance instance = startAndArm();
        player.choiceAndSolve(instance.id(), "left");
        awaitRoom(instance, Room.LEVER_ROOM);

        player.leverA(instance.id());

        // Give the engine a moment; the instance must NOT advance on lever A alone.
        await().during(ofSeconds(1)).atMost(ofSeconds(2))
                .until(() -> instance.status() == WorkflowStatus.WAITING);
        assertThat(store.view(instance.id())).map(GameView::room).hasValue(Room.LEVER_ROOM);
    }

    @Test
    @DisplayName("REQ-FUNC-003: both levers open the gate, order A->B")
    void join_opens_with_both_levers_ab() {
        lockService.forceMode(LockService.Mode.ALWAYS_SUCCEED);
        WorkflowInstance instance = startAndArm();
        player.choiceAndSolve(instance.id(), "left");
        awaitRoom(instance, Room.LEVER_ROOM);

        player.leverA(instance.id());
        player.leverB(instance.id());

        awaitStatus(instance, WorkflowStatus.COMPLETED);
        assertThat(store.trail(instance.id())).extracting(GameView::room)
                .containsSequence(Room.LEVER_ROOM, Room.TRAP_CORRIDOR, Room.TREASURE_ROOM);
    }

    @Test
    @DisplayName("REQ-FUNC-003: both levers open the gate, order B->A (order-independent)")
    void join_opens_with_both_levers_ba() {
        lockService.forceMode(LockService.Mode.ALWAYS_SUCCEED);
        WorkflowInstance instance = startAndArm();
        player.choiceAndSolve(instance.id(), "left");
        awaitRoom(instance, Room.LEVER_ROOM);

        player.leverB(instance.id());
        player.leverA(instance.id());

        awaitStatus(instance, WorkflowStatus.COMPLETED);
    }

    // === REQ-FUNC-004: trap retry + respawn =================================================

    @Test
    @DisplayName("REQ-FUNC-004: lock always jams -> exactly max attempts, then respawn to fork")
    void trap_exhausts_retries_then_respawns() {
        lockService.forceMode(LockService.Mode.ALWAYS_JAM);
        lockService.resetAttempts();
        WorkflowInstance instance = startAndArm();

        player.choiceAndSolve(instance.id(), "right");

        // On exhaustion the player is respawned to the fork (TRAP_RESPAWN view, room FORK).
        await().atMost(ofSeconds(10)).until(() ->
                store.trail(instance.id()).stream()
                        .anyMatch(v -> v.narrative().equals(Narratives.TRAP_RESPAWN.narrative())));
        assertThat(lockService.totalAttempts()).isEqualTo(3);
        // After respawn the instance is alive and waiting at the fork again, not faulted.
        awaitRoom(instance, Room.FORK);
        assertThat(instance.status()).isEqualTo(WorkflowStatus.WAITING);
    }

    @Test
    @DisplayName("REQ-FUNC-004 + 006: lock opens -> Treasure Room, instance completes")
    void trap_opens_then_victory() {
        lockService.forceMode(LockService.Mode.ALWAYS_SUCCEED);
        WorkflowInstance instance = startAndArm();

        player.choiceAndSolve(instance.id(), "right");

        awaitStatus(instance, WorkflowStatus.COMPLETED);
        GameView last = store.view(instance.id()).orElseThrow();
        assertThat(last.room()).isEqualTo(Room.TREASURE_ROOM);
        assertThat(last.victory()).isTrue();
    }

    // === REQ-FUNC-005: torch timeout ========================================================

    @Test
    @DisplayName("REQ-FUNC-005: idling past the torch timeout respawns instead of faulting")
    void torch_timeout_respawns_to_entrance() {
        // Test profile sets dungeon.fork.torch-timeout=PT2S.
        WorkflowInstance instance = startAndArm();

        // Idle: send no choice. The timeout must fire and route through TorchOut back to the
        // entrance, so the instance loops back to WAITING - it must never FAULT.
        await().atMost(ofSeconds(8)).until(() ->
                store.trail(instance.id()).stream()
                        .anyMatch(v -> v.narrative().equals(Narratives.TORCH_OUT.narrative())));
        assertThat(instance.status())
                .isIn(WorkflowStatus.WAITING, WorkflowStatus.RUNNING);
        assertThat(instance.status()).isNotEqualTo(WorkflowStatus.FAULTED);
    }

    @Test
    @DisplayName("REQ-FUNC-005: after a torch respawn the game is still completable")
    void torch_respawn_then_playable() {
        lockService.forceMode(LockService.Mode.ALWAYS_SUCCEED);
        WorkflowInstance instance = startAndArm();

        // Wait for at least one torch respawn.
        await().atMost(ofSeconds(8)).until(() ->
                store.trail(instance.id()).stream()
                        .anyMatch(v -> v.narrative().equals(Narratives.TORCH_OUT.narrative())));

        // Now play on. Re-send until it takes (a move can race a respawn re-arm).
        await().atMost(ofSeconds(10)).until(() -> {
            if (instance.status() == WorkflowStatus.WAITING) {
                player.choiceAndSolve(instance.id(), "right");
            }
            return instance.status() == WorkflowStatus.COMPLETED;
        });
        assertThat(store.view(instance.id())).map(GameView::victory).hasValue(true);
    }

    // === REQ-FUNC-008: concurrent isolation =================================================

    @Test
    @DisplayName("REQ-FUNC-008: 10 concurrent instances stay isolated; one move advances only one")
    void concurrent_instances_are_isolated() {
        lockService.forceMode(LockService.Mode.ALWAYS_SUCCEED);
        List<WorkflowInstance> instances = IntStream.range(0, 10)
                .mapToObj(i -> startAndArm())
                .toList();

        // Advance only the first player to victory.
        WorkflowInstance first = instances.getFirst();
        player.choiceAndSolve(first.id(), "right");
        awaitStatus(first, WorkflowStatus.COMPLETED);

        // Every other instance must still be waiting at the fork - unaffected by the first's move.
        instances.stream().skip(1).forEach(other -> {
            assertThat(other.status()).isEqualTo(WorkflowStatus.WAITING);
            assertThat(store.view(other.id())).map(GameView::room).hasValue(Room.FORK);
        });

        // And each can finish independently.
        instances.stream().skip(1).forEach(other -> player.choiceAndSolve(other.id(), "right"));
        instances.stream().skip(1).forEach(other -> awaitStatus(other, WorkflowStatus.COMPLETED));
    }
}
