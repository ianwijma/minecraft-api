package dev.example.mapi.internal.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory idempotency store for mutating endpoints (spec §3.2). Keys are
 * scoped per caller token and process session; the winning response is
 * replayed for matching bodies. Retention is bounded by both a TTL (24h) and
 * an entry cap, so a new process session or a busy instance invalidates old
 * keys by design.
 */
public final class IdempotencyStore {

    /** Retention window for completed responses. */
    public static final long RETENTION_MS = 24L * 60 * 60 * 1000;

    /** Maximum retained entries; the oldest completed entries are evicted. */
    public static final int MAX_ENTRIES = 1000;

    private static final class Entry {

        final String bodySha256;
        final String requestFingerprint;
        final int status;
        final String responseBody;
        final long expiresAtEpochMs;
        String locationHeader;

        Entry(String bodySha256, String requestFingerprint, int status, String responseBody) {
            this.bodySha256 = bodySha256;
            this.requestFingerprint = requestFingerprint;
            this.status = status;
            this.responseBody = responseBody;
            this.expiresAtEpochMs = System.currentTimeMillis() + RETENTION_MS;
        }
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Looks up a prior response for the key.
     *
     * @param scopeKey caller scope (token hash + process session)
     * @param key      client-supplied idempotency key
     * @param bodySha256 SHA-256 of the raw request body
     * @return the stored response, a mismatch marker, or {@code null}
     */
    public Outcome find(String scopeKey, String key, String bodySha256) {
        lock.lock();
        try {
            evictExpired();
            Entry entry = entries.get(scopeKey + '|' + key);
            if (entry == null) {
                return null;
            }
            if (!entry.bodySha256.equals(bodySha256)) {
                return Outcome.MISMATCH;
            }
            return new Outcome(entry.status, entry.responseBody, entry.locationHeader);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores a completed response for the key. If the entry already exists
     * with a different body it is left untouched (first response wins).
     *
     * @param scopeKey caller scope (token hash + process session)
     * @param key      client-supplied idempotency key
     * @param bodySha256 SHA-256 of the raw request body
     * @param requestFingerprint short non-secret request fingerprint
     * @param status   response status
     * @param responseBody response body
     * @param locationHeader optional Location header to replay
     */
    public void store(String scopeKey, String key, String bodySha256, String requestFingerprint, int status,
            String responseBody, String locationHeader) {
        lock.lock();
        try {
            evictExpired();
            String mapKey = scopeKey + '|' + key;
            Entry existing = entries.get(mapKey);
            if (existing != null && existing.bodySha256.equals(bodySha256)) {
                return;
            }
            Entry entry = new Entry(bodySha256, requestFingerprint, status, responseBody);
            entry.locationHeader = locationHeader;
            entries.put(mapKey, entry);
            while (entries.size() > MAX_ENTRIES) {
                Iterator<String> iterator = entries.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        } finally {
            lock.unlock();
        }
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        entries.values().removeIf(entry -> now > entry.expiresAtEpochMs);
    }

    /**
     * Lookup outcome.
     *
     * @param status replayed response status
     * @param responseBody replayed response body
     * @param locationHeader replayed Location header or {@code null}
     */
    public record Outcome(int status, String responseBody, String locationHeader) {

        /** Sentinel returned when the key exists with a different body. */
        public static final Outcome MISMATCH = new Outcome(-1, null, null);
    }

    /**
     * @param scopeKey caller scope
     * @param key idempotency key
     * @return a non-secret fingerprint for logs
     */
    public static String fingerprint(String scopeKey, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((scopeKey + "|" + key).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
