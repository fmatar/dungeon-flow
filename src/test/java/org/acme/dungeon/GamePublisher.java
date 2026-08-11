package org.acme.dungeon;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.serverlessworkflow.impl.WorkflowApplication;
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
