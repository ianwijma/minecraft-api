package dev.example.mapi.internal.world;

import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.job.JobManager;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.snapshot.SnapshotStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Coordinates the loaded server world session (spec §6): one unique
 * {@code worldSessionId} per session, ordered lifecycle events, and the
 * unload sequence — stop world-scoped work, terminate jobs, release
 * world-scoped leases, invalidate snapshots, never migrate pending actions
 * into the next world.
 */
public final class WorldLifecycleCoordinator {

    private final JobManager jobs;
    private final SnapshotStore snapshots;
    private final LeaseManager leases;
    private final EventBus events;

    private WorldPhase phase = WorldPhase.NONE;
    private String sessionId;
    private long sessionCounter;
    private long activatedAtEpochMs;

    /**
     * @param jobs      job manager whose world-scoped jobs are terminated
     * @param snapshots snapshot store whose world-scoped entries are invalidated
     * @param leases    lease manager whose world-scoped topics are revoked
     * @param events    ordered event bus for lifecycle events
     */
    public WorldLifecycleCoordinator(
            JobManager jobs, SnapshotStore snapshots, LeaseManager leases, EventBus events) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** @return the current phase, never {@code null} */
    public synchronized WorldPhase phase() {
        return phase;
    }

    /** @return the active (or loading) session id, if any */
    public synchronized Optional<String> currentSessionId() {
        return sessionId == null || phase == WorldPhase.NONE ? Optional.empty() : Optional.of(sessionId);
    }

    /**
     * Begins a new world session (server starting).
     *
     * @return the new session id
     * @throws IllegalStateException when a session is already loading or active
     */
    public synchronized String beginLoad() {
        if (phase == WorldPhase.LOADING || phase == WorldPhase.ACTIVE) {
            throw new IllegalStateException("world session already exists in phase " + phase);
        }
        phase = WorldPhase.LOADING;
        sessionId = "world-" + (++sessionCounter);
        publish("world.loading", Map.of("worldSessionId", sessionId));
        return sessionId;
    }

    /**
     * Marks the session active (server ready).
     *
     * @throws IllegalStateException when not loading
     */
    public synchronized void activated() {
        requirePhase(WorldPhase.LOADING, "activated");
        phase = WorldPhase.ACTIVE;
        activatedAtEpochMs = System.currentTimeMillis();
        publish("world.loaded", Map.of("worldSessionId", sessionId,
                "activatedAtEpochMs", activatedAtEpochMs));
    }

    /**
     * Begins the unload sequence (server stopping): terminates outstanding
     * world-scoped jobs, revokes world-scoped leases, invalidates world
     * snapshots, and publishes the ordered event.
     *
     * @return the unload report
     * @throws IllegalStateException when no session is loading or active
     */
    public synchronized UnloadReport beginUnload() {
        if (phase != WorldPhase.LOADING && phase != WorldPhase.ACTIVE) {
            throw new IllegalStateException("no world session to unload (phase " + phase + ")");
        }
        phase = WorldPhase.UNLOADING;
        int jobsTerminated = jobs.terminateWorld(sessionId);
        int leasesRevoked = leases.revokeTopicPrefix("world/" + sessionId + "/");
        int snapshotsInvalidated = snapshots.invalidateWorld(sessionId);
        publish("world.unloading", Map.of("worldSessionId", sessionId,
                "jobsTerminated", jobsTerminated,
                "leasesRevoked", leasesRevoked,
                "snapshotsInvalidated", snapshotsInvalidated));
        return new UnloadReport(sessionId, jobsTerminated, leasesRevoked, snapshotsInvalidated);
    }

    /**
     * Completes the unload (server fully stopped).
     *
     * @throws IllegalStateException when not unloading
     */
    public synchronized void unloaded() {
        requirePhase(WorldPhase.UNLOADING, "unloaded");
        publish("world.unloaded", Map.of("worldSessionId", sessionId));
        phase = WorldPhase.NONE;
        sessionId = null;
    }

    /**
     * Gate for world-scoped operations (spec §6).
     *
     * @param presented the world identity presented by the caller, empty when
     *                  the caller did not present one
     * @return the active session id
     * @throws ProblemException with {@code WORLD_NOT_LOADED} when no world is
     *     active, or {@code STALE_WORLD} when the presented identity refers to
     *     a previous session
     */
    public synchronized String requireActive(Optional<String> presented) {
        if (phase != WorldPhase.ACTIVE) {
            throw new ProblemException(ProblemCode.WORLD_NOT_LOADED,
                    "no active world session (phase " + phase + ")");
        }
        if (presented.isPresent() && !presented.get().equals(sessionId)) {
            throw new ProblemException(ProblemCode.STALE_WORLD,
                    "presented world identity is stale",
                    Map.of("presented", presented.get(), "current", sessionId));
        }
        return sessionId;
    }

    private void requirePhase(WorldPhase expected, String operation) {
        if (phase != expected) {
            throw new IllegalStateException("cannot " + operation + " in phase " + phase);
        }
    }

    private void publish(String type, Map<String, Object> payload) {
        events.publish(type, Optional.ofNullable(sessionId), payload);
    }

    /** Counts of outstanding work terminated during unload. */
    public record UnloadReport(
            String worldSessionId, int jobsTerminated, int leasesRevoked, int snapshotsInvalidated) {

        /** @return the report as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("worldSessionId", worldSessionId);
            map.put("jobsTerminated", jobsTerminated);
            map.put("leasesRevoked", leasesRevoked);
            map.put("snapshotsInvalidated", snapshotsInvalidated);
            return map;
        }
    }
}
