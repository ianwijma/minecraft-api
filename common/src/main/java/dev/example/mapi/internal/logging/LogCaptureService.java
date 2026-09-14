package dev.example.mapi.internal.logging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded in-memory log capture (spec §17.1): severity filtering, secret
 * redaction, cursor-based reads with explicit gaps, and capture-start
 * metadata. No global logging reconfiguration happens here — feeders are
 * attached explicitly (loader Log4j appenders land with their chunks; the
 * runtime feeds its own lifecycle events so the endpoint always has
 * meaningful content).
 */
public final class LogCaptureService {

    /** Severity levels, ordered. */
    public enum Level {
        TRACE(0), DEBUG(1), INFO(2), WARN(3), ERROR(4);

        private final int rank;

        Level(int rank) {
            this.rank = rank;
        }

        /** @return true when this level is at or above the threshold */
        public boolean atLeast(Level threshold) {
            return rank >= threshold.rank;
        }

        /** @return the wire name */
        public String wireName() {
            return name();
        }

        /** @param name case-insensitive level name
         * @return the level, never null
         * @throws IllegalArgumentException on unknown names */
        public static Level parse(String name) {
            return Level.valueOf(name.toUpperCase(Locale.ROOT));
        }
    }

    /** One captured entry. */
    public record Entry(long seq, long atEpochMs, Level level, String loggerName, String message) {

        /** @return the entry as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("seq", seq);
            map.put("atEpochMs", atEpochMs);
            map.put("level", level.wireName());
            map.put("loggerName", loggerName);
            map.put("message", message);
            return map;
        }
    }

    /** Cursor-based read result with explicit gap detection. */
    public record Entries(List<Entry> entries, long newCursor, boolean gap, long droppedUpToSeq) {
    }

    private static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final ArrayDeque<Entry> ring;
    private final java.util.concurrent.atomic.AtomicReference<List<String>> secrets =
            new java.util.concurrent.atomic.AtomicReference<>(List.of());
    private volatile Level threshold = Level.INFO;
    private long nextSeq;
    private long droppedUpToSeq;
    private final long attachedAtEpochMs = System.currentTimeMillis();

    /** Creates a capture with the default capacity and no redaction secrets. */
    public LogCaptureService() {
        this(DEFAULT_CAPACITY, List.of());
    }

    /**
     * @param capacity maximum retained entries
     * @param secrets  literal strings redacted from messages (for example the
     *                 configured bearer token)
     */
    public LogCaptureService(int capacity, List<String> secrets) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.capacity = capacity;
        this.ring = new ArrayDeque<>(capacity);
        setSecrets(secrets);
    }

    /** @return wall-clock time the capture was attached */
    public long attachedAtEpochMs() {
        return attachedAtEpochMs;
    }

    /** @param level minimum severity to retain */
    public void setThreshold(Level level) {
        this.threshold = Objects.requireNonNull(level, "level");
    }

    /**
     * Replaces the redaction secrets (for example when the configured bearer
     * token becomes known at HTTP start). Short values are ignored so that
     * redaction cannot destroy common words.
     *
     * @param secrets literal strings redacted from messages
     */
    public void setSecrets(List<String> secrets) {
        this.secrets.set(List.copyOf(Objects.requireNonNull(secrets, "secrets")));
    }

    /**
     * Records one line (subject to the threshold). Recursion is the feeder's
     * responsibility to avoid; this class performs no logging itself.
     *
     * @param level      severity
     * @param loggerName logger name
     * @param message    message text (redacted before retention)
     */
    public synchronized void record(Level level, String loggerName, String message) {
        Objects.requireNonNull(level, "level");
        if (!level.atLeast(threshold)) {
            return;
        }
        while (ring.size() >= capacity) {
            Entry dropped = ring.pollFirst();
            if (dropped != null) {
                droppedUpToSeq = dropped.seq();
            }
        }
        ring.addLast(new Entry(++nextSeq, System.currentTimeMillis(), level,
                loggerName == null ? "" : loggerName, redact(message)));
    }

    /**
     * Reads entries after a cursor.
     *
     * @param cursor last seen sequence number (0 = from the beginning)
     * @param limit  maximum entries returned, 1..1000
     * @return entries, the next cursor, and whether history below the cursor
     *     was already dropped (explicit gap, spec §13.2 style)
     */
    public synchronized Entries entriesAfter(long cursor, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        List<Entry> matched = new ArrayList<>();
        long newCursor = Math.max(cursor, droppedUpToSeq);
        for (Entry entry : ring) {
            if (entry.seq() > cursor) {
                matched.add(entry);
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
        return new Entries(List.copyOf(matched), newCursor, gap, droppedUpToSeq);
    }

    /** @return the most recent sequence number (0 before the first entry) */
    public synchronized long latestSeq() {
        return nextSeq;
    }

    private String redact(String message) {
        String redacted = message == null ? "" : message;
        for (String secret : secrets.get()) {
            if (secret != null && secret.length() >= 8 && redacted.contains(secret)) {
                redacted = redacted.replace(secret, "[redacted]");
            }
        }
        return redacted;
    }
}
