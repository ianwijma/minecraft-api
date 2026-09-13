package dev.example.mapi.internal.lease;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Exclusive, wall-clock-bounded control leases (spec §4.5, §5, §14). One
 * lease per topic at a time. A watchdog revokes expired leases immediately at
 * the API level and notifies expiry listeners so owners can perform
 * release-all cleanup (input release, tick-control restoration, and so on).
 *
 * <p>Automatic restoration on expiry is a consumer concern: tick-control
 * restoration rules (configurable, never overwriting later manual changes)
 * are implemented by the tick-control owner, not here (spec §5).
 */
public final class LeaseManager {

    private static final long WATCHDOG_PERIOD_MS = 50;

    private final ConcurrentHashMap<String, ControlLease> held = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<ControlLease>> expiryListeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService watchdog;
    private final Object idLock = new Object();
    private long idCounter;

    /** Starts the lease watchdog. */
    public LeaseManager() {
        this.watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mapi-lease-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleAtFixedRate(this::watchdogTick,
                WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Registers a listener invoked when a lease expires or is revoked by the
     * watchdog or by {@link #revokeAll(String)}. Listeners must be fast and
     * must not call back into the manager for the same lease.
     *
     * @param listener expiry listener, never {@code null}
     */
    public void onExpiry(Consumer<ControlLease> listener) {
        expiryListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Acquires the exclusive lease on {@code topic}.
     *
     * @param topic   exclusive topic (for example {@code "input"}),
     *                never blank
     * @param owner   owner label (for logging and introspection), never blank
     * @param ttlMs   time to live in wall-clock milliseconds; must be positive
     * @return the lease
     * @throws ProblemException with {@code LEASE_HELD} when another owner
     *     holds the topic
     */
    public ControlLease acquire(String topic, String owner, long ttlMs) {
        Objects.requireNonNull(topic, "topic");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        ControlLease current = held.get(topic);
        if (current != null && current.held(System.currentTimeMillis())) {
            throw new ProblemException(ProblemCode.LEASE_HELD, "topic is exclusively held",
                    Map.of("topic", topic, "owner", current.owner(),
                            "expiresAtEpochMs", current.expiresAtEpochMs()));
        }
        String id;
        synchronized (idLock) {
            id = "lease-" + (++idCounter);
        }
        ControlLease lease = new ControlLease(id, topic, owner,
                System.currentTimeMillis() + ttlMs);
        held.put(topic, lease);
        return lease;
    }

    /**
     * Extends a held lease.
     *
     * @param lease the lease to renew
     * @param ttlMs new time to live from now, positive
     * @throws ProblemException with {@code LEASE_REQUIRED} when the lease is
     *     no longer held (expired or revoked)
     */
    public void renew(ControlLease lease, long ttlMs) {
        Objects.requireNonNull(lease, "lease");
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        ControlLease current = held.get(lease.topic());
        if (current != lease || !current.held(System.currentTimeMillis())) {
            throw new ProblemException(ProblemCode.LEASE_REQUIRED, "lease is no longer held",
                    Map.of("topic", lease.topic(), "leaseId", lease.id()));
        }
        current.extend(System.currentTimeMillis() + ttlMs);
    }

    /**
     * Releases a lease voluntarily.
     *
     * @param lease the lease to release; unknown or already-released leases
     *     are ignored
     */
    public void release(ControlLease lease) {
        if (lease == null) {
            return;
        }
        ControlLease current = held.remove(lease.topic());
        if (current == lease && !lease.revoked()) {
            lease.revoke(System.currentTimeMillis());
        }
    }

    /**
     * @param topic the topic to inspect
     * @return the current holder, if the topic is held
     */
    public Optional<ControlLease> holderOf(String topic) {
        ControlLease lease = held.get(topic);
        if (lease != null && !lease.held(System.currentTimeMillis())) {
            revokeNow(lease);
            return Optional.empty();
        }
        return Optional.ofNullable(lease);
    }

    /**
     * @return all currently held leases (leak introspection for the lifecycle
     *     reliability gate, spec §18)
     */
    public List<ControlLease> activeLeases() {
        long now = System.currentTimeMillis();
        return held.values().stream().filter(lease -> lease.held(now)).toList();
    }

    /**
     * Emergency stop: revokes every lease immediately at the API level and
     * notifies expiry listeners (spec §4.5).
     *
     * @param reason human-readable reason for revocation
     * @return the number of leases revoked
     */
    public int revokeAll(String reason) {
        Objects.requireNonNull(reason, "reason");
        int count = 0;
        for (String topic : held.keySet().toArray(String[]::new)) {
            ControlLease lease = held.remove(topic);
            if (lease != null && !lease.revoked()) {
                lease.revoke(System.currentTimeMillis());
                notifyExpiry(lease);
                count++;
            }
        }
        return count;
    }

    /** Stops the watchdog. Idempotent; held leases are revoked. */
    public void shutdown() {
        watchdog.shutdownNow();
        revokeAll("lease manager shutdown");
    }

    private void watchdogTick() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ControlLease> e : held.entrySet()) {
                if (!e.getValue().held(now)) {
                    revokeNow(e.getValue());
                }
            }
        } catch (RuntimeException ignored) {
            // The watchdog must never die; next tick retries.
        }
    }

    private void revokeNow(ControlLease lease) {
        if (held.remove(lease.topic(), lease) && !lease.revoked()) {
            lease.revoke(System.currentTimeMillis());
            notifyExpiry(lease);
        }
    }

    private void notifyExpiry(ControlLease lease) {
        for (Consumer<ControlLease> listener : expiryListeners) {
            try {
                listener.accept(lease);
            } catch (RuntimeException ignored) {
                // One failing listener must not block the others.
            }
        }
    }
}
