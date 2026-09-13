package dev.example.mapi.internal.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory event log implementing the MAPI event protocol (spec §3.3):
 * per-process-session monotonic sequence numbers, a bounded ring buffer,
 * resume-by-cursor reads, and subscriber delivery. Events published after a
 * buffer overflow are unrecoverable; consumers detect this via the GAP
 * mechanism ({@link #eventsAfter} / oldest sequence tracking).
 *
 * <p>Publishing is serialized; subscribers receive events in sequence order.
 * Subscriber callbacks must be non-blocking (they typically enqueue into a
 * per-connection queue).
 */
public final class EventLog {

    /** Maximum retained events; older events are dropped (GAP then applies). */
    public static final int CAPACITY = 1024;

    /** Allowed {@code source} values (spec §3.3). */
    public static final Set<String> SOURCES = Set.of(
            "server-authoritative", "client-observed", "instrumented", "mod-provided", "api-originated");

    /**
     * Immutable event record.
     *
     * @param seq              monotonic per-process-session sequence (starts at 1)
     * @param type             event type (dotted identifier)
     * @param source           one of {@link #SOURCES}
     * @param wallClockEpochMs wall-clock timestamp
     * @param monotonicNanos   process-local monotonic timestamp
     * @param processSessionId session the event belongs to
     * @param worldSessionId   active world session or {@code null}
     * @param data             type-specific payload, never {@code null}
     */
    public record EventRecord(
            long seq,
            String type,
            String source,
            long wallClockEpochMs,
            long monotonicNanos,
            String processSessionId,
            String worldSessionId,
            Map<String, Object> data) {
    }

    /** Non-blocking subscriber callback. */
    public interface Subscriber {

        /**
         * Called for every event from registration on, in sequence order.
         *
         * @param event the event, never {@code null}
         */
        void onEvent(EventRecord event);
    }

    /** Cancellable subscription handle. */
    public interface Subscription {

        /** Stops delivery; safe to call more than once. */
        void unsubscribe();
    }

    private final String processSessionId;
    private final java.util.function.Supplier<String> worldSessionSupplier;
    private final ArrayDeque<EventRecord> buffer = new ArrayDeque<>(CAPACITY);
    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private long nextSeq = 1;

    /**
     * @param processSessionId     session identity stamped onto every event
     * @param worldSessionSupplier supplies the active world session id (or
     *                             {@code null}); read per published event
     */
    public EventLog(String processSessionId, java.util.function.Supplier<String> worldSessionSupplier) {
        this.processSessionId = Objects.requireNonNull(processSessionId, "processSessionId");
        this.worldSessionSupplier = worldSessionSupplier;
    }

    /**
     * Publishes an event to the buffer and all current subscribers.
     *
     * @param type  dotted event type, never {@code null}
     * @param source one of {@link #SOURCES}
     * @param data  type-specific payload; values must be JSON-serializable
     * @return the stored record with its assigned sequence
     */
    public synchronized EventRecord publish(String type, String source, Map<String, Object> data) {
        Objects.requireNonNull(type, "type");
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("event source must be one of " + SOURCES + ": " + source);
        }
        Map<String, Object> payload = data == null ? Map.of() : data;
        String worldSessionId = worldSessionSupplier == null ? null : worldSessionSupplier.get();
        EventRecord record = new EventRecord(nextSeq++, type, source,
                System.currentTimeMillis(), System.nanoTime(), processSessionId, worldSessionId, payload);
        buffer.addLast(record);
        while (buffer.size() > CAPACITY) {
            buffer.pollFirst();
        }
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.onEvent(record);
            } catch (RuntimeException e) {
                // Subscriber problems must never break publishing.
            }
        }
        return record;
    }

    /**
     * Registers a subscriber and replays everything after {@code after}
     * without a gap or duplicate in between: registration and replay happen
     * under the publish lock.
     *
     * @param after exclusive sequence cursor; {@code -1} for "everything retained"
     * @param subscriber non-blocking callback
     * @return subscription handle
     */
    public synchronized Subscription subscribeAfter(long after, Subscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
        for (EventRecord record : buffer) {
            if (record.seq() > after) {
                subscriber.onEvent(record);
            }
        }
        return new Subscription() {
            volatile boolean active = true;

            @Override
            public void unsubscribe() {
                if (active) {
                    active = false;
                    subscribers.remove(subscriber);
                }
            }
        };
    }

    /**
     * Events with a sequence greater than {@code after}, oldest-first.
     *
     * @param after exclusive cursor
     * @return the available page (may start later than {@code after + 1}
     *         when the buffer overflowed — consumers compare with
     *         {@link #oldestSeq()} to detect the gap)
     */
    public synchronized List<EventRecord> eventsAfter(long after) {
        List<EventRecord> out = new ArrayList<>();
        for (EventRecord record : buffer) {
            if (record.seq() > after) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * @return the oldest retained sequence, or {@code headSeq() + 1} when empty
     */
    public synchronized long oldestSeq() {
        EventRecord first = buffer.peekFirst();
        return first == null ? nextSeq : first.seq();
    }

    /**
     * @return the newest assigned sequence (0 when nothing was published)
     */
    public synchronized long headSeq() {
        return nextSeq - 1;
    }
}
