package org.acme.dungeon;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.serverlessworkflow.impl.WorkflowInstance;
import io.serverlessworkflow.impl.WorkflowStatus;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory registry of active game sessions (SRS "in-memory instance store"; no persistence, C-3
 * out-of-scope). Holds two things per instance id:
 *
 * <ul>
 *   <li>the live {@link WorkflowInstance} - so the HTTP layer can read its status and cancel it;</li>
 *   <li>the latest {@link GameView} - a projection updated by the workflow as it enters each room,
 *       giving the inspect endpoint a stable narrative to return.</li>
 * </ul>
 *
 * <p>Keeping a projection here (rather than reading room prose out of the raw workflow context) is a
 * read-model, not game logic: routing, joins, retries and timeouts all remain in the workflow
 * definition (C-1). The store is a CDI bean so workflow tasks can update it via {@code function}
 * / {@code withInstanceId} steps.
 */
@ApplicationScoped
public class GameStore {

    private static final Logger LOG = LoggerFactory.getLogger(GameStore.class);

    private final Map<String, WorkflowInstance> instances = new ConcurrentHashMap<>();
    private final Map<String, GameView> views = new ConcurrentHashMap<>();
    // Ordered history of every room a player has entered - handy for demos and used by tests to
    // assert the path taken (e.g. that a respawn happened, or the trap was entered once).
    private final Map<String, List<GameView>> trails = new ConcurrentHashMap<>();
    // Hot event bus that fans room transitions and lock attempts out to any subscribed SSE stream.
    private final BroadcastProcessor<StreamEvent> bus = BroadcastProcessor.create();

    /** Register a freshly started instance. Called by the HTTP layer right after start(). */
    public void register(WorkflowInstance instance) {
        instances.put(instance.id(), instance);
    }

    /**
     * Record that {@code instanceId} has entered a room. Called by the workflow's room-entry tasks.
     * Returns the same view so it can be used as a pass-through task output.
     */
    public GameView enter(String instanceId, GameView view) {
        views.put(instanceId, view);
        trails.computeIfAbsent(instanceId, k -> new CopyOnWriteArrayList<>()).add(view);
        LOG.info("[{}] -> {} : {}", instanceId, view.room(), view.narrative());
        bus.onNext(StreamEvent.state(instanceId, statusName(instanceId), view));
        return view;
    }

    /**
     * Record one Trap Corridor lock-pick attempt and fan it out to subscribers, so the UI can animate
     * the retry counter as the workflow actually runs it. Returns the state unchanged (pass-through).
     */
    public LockState attempt(String instanceId, LockState state) {
        bus.onNext(StreamEvent.attempt(instanceId, state.attempt(), state.picked()));
        return state;
    }

    /** Ordered list of views this instance has passed through (empty if unknown). */
    public List<GameView> trail(String instanceId) {
        return trails.getOrDefault(instanceId, List.of());
    }

    /**
     * Live event stream for one instance: first the current room (so a late subscriber sees where the
     * player is), then every subsequent transition and lock attempt.
     */
    public Multi<StreamEvent> stream(String instanceId) {
        Multi<StreamEvent> live = bus.filter(e -> instanceId.equals(e.instanceId()));
        return view(instanceId)
                .map(v -> Multi.createBy().concatenating().streams(
                        Multi.createFrom().item(StreamEvent.state(instanceId, statusName(instanceId), v)),
                        live))
                .orElse(live);
    }

    /** Current workflow status name for an instance, or RUNNING if it isn't registered yet. */
    public String statusName(String instanceId) {
        return instance(instanceId)
                .map(WorkflowInstance::status)
                .map(WorkflowStatus::name)
                .orElse(WorkflowStatus.RUNNING.name());
    }

    public Optional<GameView> view(String instanceId) {
        return Optional.ofNullable(views.get(instanceId));
    }

    public Optional<WorkflowInstance> instance(String instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    /** Snapshot of all known instance ids (facilitator race view). */
    public Map<String, WorkflowInstance> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(instances));
    }

    /** Forget an instance entirely (cleanup / reset between workshop groups). */
    public void remove(String instanceId) {
        instances.remove(instanceId);
        views.remove(instanceId);
        trails.remove(instanceId);
    }
}
