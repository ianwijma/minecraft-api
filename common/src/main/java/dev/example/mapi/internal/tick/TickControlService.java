package dev.example.mapi.internal.tick;

import dev.example.mapi.internal.lease.ControlLease;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.serverstate.ServerProgressTracker;
import dev.example.mapi.internal.world.WorldLifecycleCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exclusive, lease-owned tick control (spec §5). Mutations run on the server
 * thread via {@link TickControlBackend}; ownership is enforced with an
 * exclusive lease on the {@code tick-control} topic; automatic restoration on
 * lease expiry is configurable and never overwrites later manual changes.
 */
public final class TickControlService {

    /** A server-thread-admitted step request and its identity for later polling. */
    public record StepOperation(long generation, TickControlBackend.StepResult scheduled) {
    }

    /** Lease topic owned by tick-control users. */
    public static final String LEASE_TOPIC = "tick-control";

    private final TickControlBackend backend;
    private final LeaseManager leases;
    private final ServerProgressTracker progress;
    private final WorldLifecycleCoordinator world;
    private final float minTickRate;
    private final float maxTickRate;
    private volatile boolean restoreOnExpiry;
    private volatile ControlLease lease;
    private String activeStepLeaseId;
    private long stepGeneration;
    private long activeStepGeneration;

    /**
     * @param backend       loader backend, never {@code null}
     * @param leases        lease manager for the exclusive control lease
     * @param progress      progress tracker (state queries and gates)
     * @param world         world lifecycle (world-scoped gating)
     * @param minTickRate   configured minimum tick rate
     * @param maxTickRate   configured maximum tick rate
     */
    public TickControlService(TickControlBackend backend, LeaseManager leases,
            ServerProgressTracker progress, WorldLifecycleCoordinator world,
            float minTickRate, float maxTickRate) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.progress = Objects.requireNonNull(progress, "progress");
        this.world = Objects.requireNonNull(world, "world");
        if (minTickRate <= 0 || maxTickRate < minTickRate) {
            throw new IllegalArgumentException("tick rate bounds invalid");
        }
        this.minTickRate = minTickRate;
        this.maxTickRate = maxTickRate;
    }

    /** @return true when lease-expiry restores prior tick-control state */
    public boolean restoreOnLeaseExpiry() {
        return restoreOnExpiry;
    }

    /** @param restore true to restore prior state when the lease expires */
    public void setRestoreOnLeaseExpiry(boolean restore) {
        this.restoreOnExpiry = restore;
    }

    /**
     * Acquires the exclusive tick-control lease.
     *
     * @param owner owner label
     * @param ttlMs lease time to live
     * @return the lease
     * @throws ProblemException with {@code LEASE_HELD} when someone else owns
     *     tick control
     */
    public ControlLease acquireLease(String owner, long ttlMs) {
        ControlLease acquired = leases.acquire(LEASE_TOPIC, owner, ttlMs);
        if (activeStepLeaseId != null && !activeStepLeaseId.equals(acquired.id())) {
            backend.stopStepping();
            activeStepLeaseId = null;
            activeStepGeneration = 0;
        }
        this.lease = acquired;
        return acquired;
    }

    /**
     * @param lease the lease to check
     * @throws ProblemException with {@code LEASE_REQUIRED} when the caller
     *     does not own tick control right now
     */
    public void requireOwnership(ControlLease lease) {
        if (lease == null) {
            throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                    "tick control requires owning the " + LEASE_TOPIC + " lease");
        }
        var holder = leases.holderOf(LEASE_TOPIC);
        if (holder.isEmpty() || !holder.get().id().equals(lease.id())) {
            throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                    "tick-control lease is not held by this caller",
                    Map.of("topic", LEASE_TOPIC));
        }
    }

    /**
     * Resolves the current tick-control holder by lease id (HTTP callers only
     * have the id).
     *
     * @param leaseId the lease id presented by the caller
     * @return the held lease
     * @throws ProblemException with {@code LEASE_REQUIRED} when no lease with
     *     this id currently owns tick control
     */
    public ControlLease holderFor(String leaseId) {
        var holder = leases.holderOf(LEASE_TOPIC);
        if (holder.isEmpty() || leaseId == null || !holder.get().id().equals(leaseId)) {
            throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                    "tick-control lease is not held by this caller",
                    Map.of("topic", LEASE_TOPIC,
                            "holderId", holder.map(ControlLease::id).orElse("none")));
        }
        return holder.get();
    }

    /** @return the id of the current tick-control holder, if any */
    public Optional<String> holderId() {
        return leases.holderOf(LEASE_TOPIC).map(ControlLease::id);
    }

    /** @return the current tick-control state, never {@code null} */
    public TickControlBackend.State state() {
        return backend.state();
    }

    /**
     * Freezes the tick loop. Must run on the server thread; callers arrange
     * threading (HTTP layer uses its bounded server-thread runner).
     *
     * @param lease caller's tick-control lease
     * @return the state after the operation
     */
    public TickControlBackend.State freeze(ControlLease lease) {
        requireOwnership(lease);
        backend.freeze();
        var after = backend.state();
        progress.observe(observation(after));
        return after;
    }

    /**
     * Unfreezes the tick loop. Must run on the server thread.
     *
     * @param lease caller's tick-control lease
     * @return the state after the operation
     */
    public TickControlBackend.State unfreeze(ControlLease lease) {
        requireOwnership(lease);
        stopActiveStep();
        backend.unfreeze();
        var after = backend.state();
        progress.observe(observation(after));
        return after;
    }

    /**
     * Sets the tick rate within configured bounds.
     *
     * @param lease caller's tick-control lease
     * @param rate  requested rate
     * @return the rate actually applied
     * @throws ProblemException with {@code BAD_REQUEST} when the rate is out
     *     of the configured bounds; routes reject unsupported rate control
     *     before reaching this method (no silent fallback, spec §3.3)
     */
    public float setTickRate(ControlLease lease, float rate) {
        requireOwnership(lease);
        if (rate < minTickRate || rate > maxTickRate) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "tick rate must be between " + minTickRate + " and " + maxTickRate,
                    Map.of("min", minTickRate, "max", maxTickRate, "requested", rate));
        }
        return backend.setTickRate(rate).orElseThrow();
    }

    /** @return true when rate control is supported */
    public boolean supportsRate() {
        return backend.supportsRate();
    }

    /** @return true when stepping is supported */
    public boolean supportsStepping() {
        return backend.supportsStepping();
    }

    /** @return true when sprinting is supported */
    public boolean supportsSprinting() {
        return backend.supportsSprinting();
    }

    /**
     * Admits a bounded simulation step; the job worker observes completion
     * through {@link #stepState(ControlLease, StepOperation)}.
     *
     * @param lease caller's tick-control lease
     * @param ticks ticks to step, 1..10000
     * @return the scheduled operation and its identity
     */
    public StepOperation step(ControlLease lease, int ticks) {
        requireOwnership(lease);
        if (ticks < 1 || ticks > 10_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 10000");
        }
        if (activeStepLeaseId != null) {
            throw new ProblemException(ProblemCode.SERVER_BUSY,
                    "a tick step is already active");
        }
        TickControlBackend.StepResult result = backend.step(ticks);
        long generation = ++stepGeneration;
        if (result.completed() < result.requested()) {
            activeStepLeaseId = lease.id();
            activeStepGeneration = generation;
        }
        return new StepOperation(generation, result);
    }

    /** Returns state only while this admitted step remains active and lease-owned. */
    public TickControlBackend.State stepState(ControlLease lease, StepOperation operation) {
        requireOwnership(lease);
        if (operation == null || operation.generation() != activeStepGeneration
                || !lease.id().equals(activeStepLeaseId)) {
            throw new ProblemException(ProblemCode.SERVER_PAUSED,
                    "the admitted tick step was stopped or replaced before completion");
        }
        return backend.state();
    }

    /** Clears an asynchronously completed step owned by {@code lease}. */
    public void completeStep(ControlLease lease, StepOperation operation) {
        if (lease != null && operation != null && operation.generation() == activeStepGeneration
                && lease.id().equals(activeStepLeaseId)) {
            activeStepLeaseId = null;
            activeStepGeneration = 0;
        }
    }

    /** Stops and clears an incomplete step previously admitted for {@code lease}. */
    public void cancelStep(ControlLease lease, StepOperation operation) {
        if (lease != null && operation != null && operation.generation() == activeStepGeneration
                && lease.id().equals(activeStepLeaseId)) {
            backend.stopStepping();
            activeStepLeaseId = null;
            activeStepGeneration = 0;
        }
    }

    /**
     * Sprints the simulation a bounded number of ticks.
     *
     * @param lease caller's tick-control lease
     * @param ticks ticks to sprint, 1..10000
     * @return the sprint result (completion is tracked via {@link #state()}
     *     polling; vanilla sprinting is asynchronous)
     */
    public TickControlBackend.StepResult sprint(ControlLease lease, int ticks) {
        requireOwnership(lease);
        if (ticks < 1 || ticks > 10_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 10000");
        }
        return backend.sprint(ticks).orElseThrow();
    }

    /**
     * Stops an active step.
     *
     * @param lease caller's tick-control lease
     * @return true when an active step was stopped
     */
    public boolean stopStepping(ControlLease lease) {
        requireOwnership(lease);
        boolean stopped = backend.stopStepping();
        if (activeStepLeaseId != null) {
            activeStepLeaseId = null;
            activeStepGeneration = 0;
        }
        return stopped;
    }

    /**
     * Stops an active sprint.
     *
     * @param lease caller's tick-control lease
     * @return true when an active sprint was stopped
     */
    public boolean stopSprinting(ControlLease lease) {
        requireOwnership(lease);
        return backend.stopSprinting();
    }

    /** @return the configured tick-rate bounds */
    public Map<String, Object> rateBounds() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("min", minTickRate);
        map.put("max", maxTickRate);
        return map;
    }

    private dev.example.mapi.internal.serverstate.TickObservation observation(TickControlBackend.State state) {
        return new dev.example.mapi.internal.serverstate.TickObservation(
                System.currentTimeMillis(), state.tickCount(), state.frozen(), state.sprinting());
    }

    private void stopActiveStep() {
        if (activeStepLeaseId != null) {
            backend.stopStepping();
            activeStepLeaseId = null;
            activeStepGeneration = 0;
        }
    }
}
