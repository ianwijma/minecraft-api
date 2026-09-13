package dev.example.mapi.internal.lease;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Control leases (spec §5.3): expiring, renewable grants for mutually
 * exclusive client/server control surfaces. Conflict policy is chosen at
 * acquire time — {@code reject} (409 LEASE_HELD), {@code queue} (FIFO
 * activation on release/expiry), or {@code preempt} (revoke the current
 * holder). Expiry, release, preemption, and disconnect run the registered
 * per-type hook (e.g. releasing API-held keys for {@code client.input}).
 */
public final class LeaseManager {

    /** Lease types (spec §5.3). */
    public static final Set<String> TYPES = Set.of(
            "client.input", "client.ui", "client.camera", "server.tick", "world.bulkEdit");

    /** Default TTL when a request omits one. */
    public static final long DEFAULT_TTL_MS = 60_000;

    /** Lease states, in wire form. */
    public enum State {
        QUEUED, HELD, RELEASED, EXPIRED, PREEMPTED;

        /** @return the wire name */
        public String wireName() {
            return switch (this) {
                case QUEUED -> "queued";
                case HELD -> "held";
                case RELEASED -> "released";
                case EXPIRED -> "expired";
                case PREEMPTED -> "preempted";
            };
        }
    }

    /** Raised when the conflict policy is {@code reject} and a lease is held. */
    public static final class LeaseHeldException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** @return the currently held lease */
        public final Snapshot held;

        LeaseHeldException(Snapshot held) {
            super("lease already held: " + held.lease() + " (expires at " + held.expiresAtEpochMs() + ")");
            this.held = held;
        }
    }

    /**
     * Immutable lease description.
     *
     * @param id                lease id
     * @param lease             lease type
     * @param state             wire state
     * @param acquiredAtEpochMs creation time
     * @param expiresAtEpochMs  current expiry (wall clock)
     * @param holder            non-secret holder fingerprint
     */
    public record Snapshot(
            String id,
            String lease,
            String state,
            long acquiredAtEpochMs,
            long expiresAtEpochMs,
            String holder) {
    }

    private static final class LeaseState {

        final String id = UUID.randomUUID().toString();
        final String type;
        final long acquiredAtEpochMs = System.currentTimeMillis();
        String holder;
        volatile State state;
        volatile long expiresAtEpochMs;

        LeaseState(String type, String holder, State state, long expiresAtEpochMs) {
            this.type = type;
            this.holder = holder;
            this.state = state;
            this.expiresAtEpochMs = expiresAtEpochMs;
        }

        Snapshot snapshot() {
            return new Snapshot(id, type, state.wireName(), acquiredAtEpochMs, expiresAtEpochMs, holder);
        }
    }

    private final Logger logger;
    private final java.util.function.Consumer<Snapshot> onChange;
    private final Map<String, java.lang.Runnable> expiryHooks = new ConcurrentHashMap<>();
    private final Map<String, LeaseState> leases = new ConcurrentHashMap<>();
    private final Map<String, TypeState> byType = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog;
    private final AtomicLong leasesCreated = new AtomicLong();

    private static final class TypeState {

        LeaseState current;
        final Deque<LeaseState> queue = new ArrayDeque<>();
    }

    /**
     * @param logger   platform logger
     * @param onChange change observer ({@code lease.changed} events); may be
     *                 {@code null}
     */
    public LeaseManager(Logger logger, java.util.function.Consumer<Snapshot> onChange) {
        this.logger = logger;
        this.onChange = onChange;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mapi-lease-watchdog");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Registers the hook run when a lease of this type ends by expiry,
     * release, or preemption (e.g. release API-held keys).
     *
     * @param type lease type
     * @param hook the hook
     */
    public void registerExpiryHook(String type, Runnable hook) {
        expiryHooks.put(type, hook);
    }

    /**
     * Acquires a lease.
     *
     * @param type     lease type from {@link #TYPES}
     * @param ttlMs    time-to-live, clamped to [1000, 3_600_000]
     * @param conflict {@code reject}, {@code queue}, or {@code preempt}
     * @param holder   non-secret holder fingerprint
     * @return the new snapshot (held or queued)
     * @throws LeaseHeldException when the policy is {@code reject} and a
     *                            lease is currently held
     */
    public synchronized Snapshot acquire(String type, Long ttlMs, String conflict, String holder) {
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown lease type: " + type);
        }
        String policy = conflict == null ? "reject" : conflict;
        if (!Set.of("reject", "queue", "preempt").contains(policy)) {
            throw new IllegalArgumentException("conflict must be reject, queue, or preempt: " + policy);
        }
        long ttl = Math.clamp(ttlMs == null ? DEFAULT_TTL_MS : ttlMs, 1_000L, 3_600_000L);
        long expiresAt = System.currentTimeMillis() + ttl;
        TypeState state = byType.computeIfAbsent(type, k -> new TypeState());
        if (state.current != null && state.current.state == State.HELD) {
            switch (policy) {
                case "reject" -> throw new LeaseHeldException(state.current.snapshot());
                case "preempt" -> {
                    state.current.state = State.PREEMPTED;
                    fireHook(state.current.type);
                    emit(state.current.snapshot());
                }
                default -> {
                    LeaseState queued = new LeaseState(type, holder, State.QUEUED, 0);
                    leases.put(queued.id, queued);
                    state.queue.addLast(queued);
                    leasesCreated.incrementAndGet();
                    emit(queued.snapshot());
                    return queued.snapshot();
                }
            }
        }
        LeaseState lease = new LeaseState(type, holder, State.HELD, expiresAt);
        state.current = lease;
        leases.put(lease.id, lease);
        leasesCreated.incrementAndGet();
        scheduleExpiry(state, lease);
        emit(lease.snapshot());
        return lease.snapshot();
    }

    /**
     * Renews a held lease.
     *
     * @param id    lease id
     * @param ttlMs new time-to-live from now, clamped like acquire
     * @return the refreshed snapshot
     * @throws IllegalArgumentException when unknown
     * @throws IllegalStateException    when the lease is not held
     */
    public synchronized Snapshot renew(String id, Long ttlMs) {
        LeaseState lease = leases.get(id);
        if (lease == null) {
            throw new IllegalArgumentException("unknown lease: " + id);
        }
        if (lease.state != State.HELD) {
            throw new IllegalStateException("lease is not held (state " + lease.state.wireName() + ")");
        }
        long ttl = Math.clamp(ttlMs == null ? DEFAULT_TTL_MS : ttlMs, 1_000L, 3_600_000L);
        lease.expiresAtEpochMs = System.currentTimeMillis() + ttl;
        scheduleExpiry(byType.computeIfAbsent(lease.type, k -> new TypeState()), lease);
        emit(lease.snapshot());
        return lease.snapshot();
    }

    /**
     * Releases a lease (held or queued).
     *
     * @param id lease id
     * @return the post-release snapshot
     * @throws IllegalArgumentException when unknown
     * @throws IllegalStateException    when the lease is already terminal
     */
    public synchronized Snapshot release(String id) {
        LeaseState lease = leases.get(id);
        if (lease == null) {
            throw new IllegalArgumentException("unknown lease: " + id);
        }
        if (lease.state != State.HELD && lease.state != State.QUEUED) {
            throw new IllegalStateException("lease already terminal (state " + lease.state.wireName() + ")");
        }
        if (lease.state == State.QUEUED) {
            TypeState state = byType.get(lease.type);
            if (state != null) {
                state.queue.remove(lease);
            }
            lease.state = State.RELEASED;
            emit(lease.snapshot());
            return lease.snapshot();
        }
        lease.state = State.RELEASED;
        fireHook(lease.type);
        promote(byType.get(lease.type));
        emit(lease.snapshot());
        return lease.snapshot();
    }

    /**
     * Releases every lease (API shutdown; hooks run for held leases).
     */
    public synchronized void releaseAll() {
        for (LeaseState lease : List.copyOf(leases.values())) {
            if (lease.state == State.HELD) {
                lease.state = State.RELEASED;
                fireHook(lease.type);
                TypeState state = byType.get(lease.type);
                if (state != null && state.current == lease) {
                    state.current = null;
                }
                emit(lease.snapshot());
            } else if (lease.state == State.QUEUED) {
                lease.state = State.RELEASED;
                emit(lease.snapshot());
            }
        }
    }

    /**
     * Stops the watchdog (shutdown).
     */
    public synchronized void shutdown() {
        watchdog.shutdownNow();
    }

    /**
     * @return all lease snapshots newest-first
     */
    public synchronized List<Snapshot> list() {
        return leases.values().stream()
                .sorted(java.util.Comparator.comparingLong((LeaseState l) -> l.acquiredAtEpochMs).reversed())
                .map(LeaseState::snapshot)
                .toList();
    }

    /**
     * @return the snapshot or {@code null} when unknown
     */
    public Snapshot get(String id) {
        LeaseState lease = leases.get(id);
        return lease == null ? null : lease.snapshot();
    }

    /**
     * @return total leases ever created (observability)
     */
    public long totalCreated() {
        return leasesCreated.get();
    }

    private void scheduleExpiry(TypeState state, LeaseState lease) {
        long delay = Math.max(0, lease.expiresAtEpochMs - System.currentTimeMillis());
        watchdog.schedule(() -> {
            synchronized (this) {
                if (lease.state == State.HELD && System.currentTimeMillis() >= lease.expiresAtEpochMs) {
                    lease.state = State.EXPIRED;
                    fireHook(lease.type);
                    promote(state);
                    emit(lease.snapshot());
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void promote(TypeState state) {
        if (state == null) {
            return;
        }
        if (state.current != null && state.current.state == State.HELD) {
            return;
        }
        LeaseState next = state.queue.pollFirst();
        while (next != null && next.state != State.QUEUED) {
            next = state.queue.pollFirst();
        }
        if (next == null) {
            state.current = null;
            return;
        }
        next.state = State.HELD;
        next.expiresAtEpochMs = System.currentTimeMillis() + DEFAULT_TTL_MS;
        state.current = next;
        scheduleExpiry(state, next);
        emit(next.snapshot());
    }

    private void fireHook(String type) {
        java.lang.Runnable hook = expiryHooks.get(type);
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException e) {
            logger.warn("MAPI: lease expiry hook for {} failed", type, e);
        }
    }

    private void emit(Snapshot snapshot) {
        if (onChange == null) {
            return;
        }
        try {
            onChange.accept(snapshot);
        } catch (RuntimeException ignored) {
            // Observers must never break lease handling.
        }
    }
}
