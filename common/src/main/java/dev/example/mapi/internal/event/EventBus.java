package dev.example.mapi.internal.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ordered event bus with bounded retention, resume cursors, explicit gap
 * detection, and blocking waits (spec §6, §13.2). Events carry a
 * monotonically increasing sequence number; consumers establish a cursor
 * (typically the current {@link #latestSeq()}) <em>before</em> triggering an
 * action and then wait from that cursor, which eliminates the
 * "event occurred before subscription" race.
 *
 * <p>Retention is a bounded ring: when older events are dropped, a consumer
 * presenting a cursor below the oldest retained event observes an explicit
 * gap rather than silently missing history.
 */
public final class EventBus {

    /** Result of a cursor-based poll. */
    public record EventsAfter(List<Event> events, long newCursor, boolean gap,
            long oldestAvailableSeq, long latestSeq) {
    }

    private static final int DEFAULT_RETENTION = 1024;

    private final int retention;
    private final ArrayDeque<Event> ring;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition published = lock.newCondition();
    private long nextSeq;
    private long droppedUpToSeq;

    /** Creates a bus with {@link #DEFAULT_RETENTION} events. */
    public EventBus() {
        this(DEFAULT_RETENTION);
    }

    /**
     * @param retention how many events are retained for cursor-based resume
     */
    public EventBus(int retention) {
        if (retention < 1) {
            throw new IllegalArgumentException("retention must be at least 1");
        }
        this.retention = retention;
        this.ring = new ArrayDeque<>(retention);
    }

    /**
     * Publishes an event with the next sequence number.
     *
     * @param type            event type, never blank
     * @param worldSessionId  world scope, empty for process-scoped events
     * @param payload         JSON-able payload, may be {@code null}
     * @return the published event
     */
    public Event publish(String type, Optional<String> worldSessionId, Map<String, Object> payload) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("event type must not be blank");
        }
        lock.lock();
        try {
            Event event = new Event(++nextSeq, type, System.currentTimeMillis(),
                    worldSessionId == null ? Optional.empty() : worldSessionId,
                    payload == null ? Map.of() : Map.copyOf(payload));
            while (ring.size() >= retention) {
                Event dropped = ring.pollFirst();
                if (dropped != null) {
                    droppedUpToSeq = dropped.seq();
                }
            }
            ring.addLast(event);
            published.signalAll();
            return event;
        } finally {
            lock.unlock();
        }
    }

    /** @return the most recent sequence number (0 before the first event) */
    public long latestSeq() {
        lock.lock();
        try {
            return nextSeq;
        } finally {
            lock.unlock();
        }
    }

    /** @return the sequence number below which events were dropped (0 if none) */
    public long droppedUpToSeq() {
        lock.lock();
        try {
            return droppedUpToSeq;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Polls events after a cursor. A cursor of {@code latestSeq()} at wait
     * time is the canonical "subscribe before triggering" position.
     *
     * @param cursor last sequence number the consumer has seen (0 = from the
     *               beginning)
     * @param filter declarative filter, never {@code null}
     * @param limit  maximum events returned; 1..1000
     * @return matching events, the cursor to present next time, and whether
     *     history below the cursor was already dropped (explicit gap)
     */
    public EventsAfter eventsAfter(long cursor, EventFilter filter, int limit) {
        Objects.requireNonNull(filter, "filter");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        lock.lock();
        try {
            List<Event> matched = new ArrayList<>();
            long newCursor = Math.max(cursor, droppedUpToSeq);
            for (Event event : ring) {
                if (event.seq() > cursor && filter.matches(event)) {
                    matched.add(event);
                    if (matched.size() >= limit) {
                        break;
                    }
                }
            }
            if (!matched.isEmpty()) {
                newCursor = matched.get(matched.size() - 1).seq();
            } else if (cursor < droppedUpToSeq) {
                newCursor = droppedUpToSeq;
            }
            boolean gap = cursor > 0 && cursor < droppedUpToSeq;
            return new EventsAfter(List.copyOf(matched), newCursor, gap, droppedUpToSeq, nextSeq);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for an event matching the filter with a sequence number greater
     * than {@code afterSeq}. Already-published matching events return
     * immediately; the caller can therefore establish the cursor before
     * triggering the action that produces the event.
     *
     * @param filter   declarative filter, never {@code null}
     * @param afterSeq wait for events strictly after this sequence number
     * @param timeoutMs wall-clock bound in milliseconds
     * @return the matching event, or empty on timeout
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public Optional<Event> waitForEvent(EventFilter filter, long afterSeq, long timeoutMs)
            throws InterruptedException {
        Objects.requireNonNull(filter, "filter");
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        lock.lock();
        try {
            while (true) {
                for (Event event : ring) {
                    if (event.seq() > afterSeq && filter.matches(event)) {
                        return Optional.of(event);
                    }
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    return Optional.empty();
                }
                published.awaitNanos(remainingNanos);
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return the maximum number of retained events */
    public int retention() {
        return retention;
    }
}
