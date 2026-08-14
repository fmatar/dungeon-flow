package org.acme.dungeon;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.events.EventPublisher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Test helper that publishes correlated player-move CloudEvents straight into the engine broker,
 * mirroring what {@link DungeonResource} does over HTTP. Lets workflow-level tests drive a game
 * without going through REST.
 */
@ApplicationScoped
public class GamePublisher {

    private static final URI SOURCE = URI.create("test:/dungeon");

    @Inject
    WorkflowApplication app;

    @Inject
    ObjectMapper mapper;

    public void choice(String instanceId, String direction) {
        publish(instanceId, GameEvents.CHOICE, json(new PlayerMove(direction)));
    }

    @Inject
    GameStore store;

    @Inject
    RiddleService riddleService;

    /**
     * Submit one riddle answer, mirroring what {@link DungeonResource#answerRiddle} does: stash the
     * answer, then fire the trigger event.
     */
    public void riddleAnswer(String instanceId, String answer) {
        store.submitAnswer(instanceId, answer);
        publish(instanceId, GameEvents.RIDDLE_ANSWER, "{}".getBytes());
    }

    /**
     * Choose a direction and then solve whatever riddle gates it, so a test that cares about a room
     * beyond the gate does not have to know which riddle was posed.
     *
     * @throws AssertionError if no gate appears, since silently proceeding would make the eventual
     *                        failure look like a routing bug rather than a missing gate
     */
    public void choiceAndSolve(String instanceId, String direction) {
        choice(instanceId, direction);
        solveGate(instanceId);
    }

    /** Answer the currently posed gate correctly, waiting until it can actually receive the answer. */
    public void solveGate(String instanceId) {
        RiddleView posed = awaitGate(instanceId);
        answerWhenArmed(instanceId, canonicalAnswerFor(posed.riddleId()));
    }

    /**
     * Submit an answer only once the instance is parked on the answer listen.
     *
     * <p>This matters: the gate publishes its riddle from {@code PoseRiddle}, which runs <em>before</em>
     * the engine arms the {@code AwaitAnswer} listen. Firing the trigger in that window means nothing
     * is listening and the event is dropped - the instance then waits forever and the test fails
     * looking like a routing bug. {@code DungeonResource.awaitArmed} exists for the same reason.
     */
    public void answerWhenArmed(String instanceId, String answer) {
        awaitWaiting(instanceId);
        riddleAnswer(instanceId, answer);
    }

    /** Block briefly until a gate is posed for this instance, and return it. */
    public RiddleView awaitGate(String instanceId) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            var posed = store.riddle(instanceId);
            if (posed.isPresent()) {
                return posed.get();
            }
            sleep();
        }
        throw new AssertionError("no riddle gate was posed for instance " + instanceId);
    }

    /** Block until the instance is WAITING (i.e. parked on a listen) or terminal. */
    public void awaitWaiting(String instanceId) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            var status = store.instance(instanceId).map(i -> i.status()).orElse(null);
            if (status == WorkflowStatus.WAITING || status == WorkflowStatus.COMPLETED
                    || status == WorkflowStatus.FAULTED || status == WorkflowStatus.CANCELLED) {
                return;
            }
            sleep();
        }
    }

    /** Wait until the posed gate reports at least {@code attempt} graded attempts, or is cleared. */
    public void awaitGraded(String instanceId, int attempt) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            var posed = store.riddle(instanceId);
            if (posed.isEmpty() || posed.get().attempt() >= attempt) {
                return;
            }
            sleep();
        }
    }

    /** The canonical (always-correct) answer for a posed riddle id. */
    public String canonicalAnswerFor(String riddleId) {
        return Riddles.all().stream()
                .filter(r -> r.id().equals(riddleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("unknown riddle " + riddleId))
                .canonical();
    }

    private static void sleep() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void leverA(String instanceId) {
        publish(instanceId, GameEvents.LEVER_A, "{}".getBytes());
    }

    public void leverB(String instanceId) {
        publish(instanceId, GameEvents.LEVER_B, "{}".getBytes());
    }

    private void publish(String instanceId, String type, byte[] data) {
        EventPublisher publisher = app.eventPublishers().iterator().next();
        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(SOURCE)
                .withType(type)
                .withDataContentType("application/json")
                .withExtension(GameEvents.CORRELATION_ATTR, instanceId)
                .withData(data)
                .build();
        publisher.publish(event).toCompletableFuture().join();
    }

    private byte[] json(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
