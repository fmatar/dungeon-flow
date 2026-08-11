package org.acme.dungeon;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.RestStreamElementType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;
import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.serverlessworkflow.impl.events.EventPublisher;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The player-facing HTTP API. It does three things and nothing more: start a session (a workflow
 * instance), turn player moves into correlated CloudEvents, and report a session's state. The game
 * rules live in {@link DungeonWorkflow}; this class never decides what happens next.
 *
 * <p>Moves are published straight into the engine's in-process event broker via
 * {@link WorkflowApplication#eventPublishers()} - no Kafka, no in-memory messaging channel, so the
 * whole game is a single container with no external services (C-3).
 */
@Path("/dungeon")
@Produces(MediaType.APPLICATION_JSON)
public class DungeonResource {

    private static final Logger LOG = LoggerFactory.getLogger(DungeonResource.class);
    private static final URI SOURCE = URI.create("dungeon:/api");

    /** How long to wait for a freshly started instance to arm its first listen before returning. */
    private static final long READY_TIMEOUT_MS = 3_000;

    @Inject
    DungeonWorkflow workflow;

    @Inject
    WorkflowApplication app;

    @Inject
    GameStore store;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "dungeon.fork.torch-timeout", defaultValue = "PT60S")
    String torchTimeout;

    // === Responses ==========================================================================

    public record StartResponse(String instanceId, GameView entrance, long torchTimeoutSeconds) {
    }

    public record StateResponse(String instanceId, String status, GameView view) {
    }

    // === REQ-FUNC-001: start a game instance ================================================

    @POST
    public Response start() {
        WorkflowInstance instance = workflow.instance(Map.of());
        store.register(instance);
        // Fire-and-forget: the engine runs the opening rooms and parks at the fork listen.
        instance.start();
        awaitArmed(instance);
        LOG.info("Started dungeon instance {}", instance.id());
        // The opening scene is always the Entrance (REQ-FUNC-001); the player is now at the fork.
        return Response.status(Response.Status.CREATED)
                .entity(new StartResponse(instance.id(), Narratives.ENTRANCE, torchSeconds()))
                .build();
    }

    // === Live updates: SSE stream of room transitions + lock attempts =======================

    @GET
    @Path("/{id}/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<StreamEvent> stream(@PathParam("id") String id) {
        return store.stream(id);
    }

    private long torchSeconds() {
        try {
            return Duration.parse(torchTimeout).getSeconds();
        } catch (Exception e) {
            return 60;
        }
    }

    // === REQ-FUNC-002/003: player moves -> correlated CloudEvents ===========================

    @POST
    @Path("/{id}/choice")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response choose(@PathParam("id") String id, PlayerMove move) {
        if (store.instance(id).isEmpty()) {
            return notFound(id);
        }
        publish(id, GameEvents.CHOICE, toJson(move));
        return Response.accepted().build();
    }

    @POST
    @Path("/{id}/lever-a")
    public Response leverA(@PathParam("id") String id) {
        return pullLever(id, GameEvents.LEVER_A);
    }

    @POST
    @Path("/{id}/lever-b")
    public Response leverB(@PathParam("id") String id) {
        return pullLever(id, GameEvents.LEVER_B);
    }

    private Response pullLever(String id, String type) {
        if (store.instance(id).isEmpty()) {
            return notFound(id);
        }
        publish(id, type, "{}".getBytes());
        return Response.accepted().build();
    }

    // === REQ-FUNC-007: inspect a session ====================================================

    @GET
    @Path("/{id}")
    public Response inspect(@PathParam("id") String id) {
        Optional<WorkflowInstance> instance = store.instance(id);
        if (instance.isEmpty()) {
            // Unknown or already cleaned-up id. Per REQ-FUNC-007 this 404 is the "absent" signal.
            return notFound(id);
        }
        WorkflowStatus status = instance.get().status();
        GameView view = store.view(id).orElse(Narratives.ENTRANCE);
        // Note: we return 200 with the victory view on completion so the player actually sees the win
        // (REQ-FUNC-006). Strict REQ-FUNC-007 "404 after completion" is a one-line change here if
        // preferred; see README > Design decisions.
        return Response.ok(new StateResponse(id, status.name(), view)).build();
    }

    // === REQ-FUNC-008/012 support: race view + cleanup ======================================

    @GET
    public List<StateResponse> list() {
        List<StateResponse> out = new ArrayList<>();
        store.all().forEach((id, instance) ->
                out.add(new StateResponse(id, instance.status().name(),
                        store.view(id).orElse(Narratives.ENTRANCE))));
        return out;
    }

    @DELETE
    @Path("/{id}")
    public Response cleanup(@PathParam("id") String id) {
        Optional<WorkflowInstance> instance = store.instance(id);
        if (instance.isEmpty()) {
            return notFound(id);
        }
        instance.get().cancel();
        store.remove(id);
        LOG.info("Cleaned up dungeon instance {}", id);
        return Response.noContent().build();
    }

    // === helpers ============================================================================

    private void publish(String instanceId, String type, byte[] data) {
        EventPublisher publisher = app.eventPublishers().iterator().next();
        CloudEvent event = CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(SOURCE)
                .withType(type)
                .withDataContentType(MediaType.APPLICATION_JSON)
                .withExtension(GameEvents.CORRELATION_ATTR, instanceId)
                .withData(data)
                .build();
        LOG.info("Publishing {} to instance {}", type, instanceId);
        publisher.publish(event).toCompletableFuture().join();
    }

    private byte[] toJson(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize move payload", e);
        }
    }

    /** Block briefly until the instance is parked at a listen (or terminal), so no move is lost. */
    private void awaitArmed(WorkflowInstance instance) {
        long deadline = System.nanoTime() + READY_TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            WorkflowStatus status = instance.status();
            if (status == WorkflowStatus.WAITING
                    || status == WorkflowStatus.COMPLETED
                    || status == WorkflowStatus.FAULTED
                    || status == WorkflowStatus.CANCELLED) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Response notFound(String id) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "No active dungeon instance with id " + id))
                .build();
    }
}
