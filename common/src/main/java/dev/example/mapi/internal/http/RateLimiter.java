package dev.example.mapi.internal.http;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter, one window per client key (loopback address).
 * Thread-safe; sized for a loopback-only listener, so the number of keys is
 * naturally bounded and stale windows are pruned on access.
 */
public final class RateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final int limitPerMinute;
    private final long windowMs;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @param limitPerMinute maximum requests per client per sliding 60s
     *                       window; must be positive
     */
    public RateLimiter(int limitPerMinute) {
        this(limitPerMinute, WINDOW_MS);
    }

    RateLimiter(int limitPerMinute, long windowMs) {
        if (limitPerMinute < 1) {
            throw new IllegalArgumentException("limitPerMinute must be positive");
        }
        this.limitPerMinute = limitPerMinute;
        this.windowMs = windowMs;
    }

    /**
     * Tries to acquire one request slot for the client.
     *
     * @param clientKey stable client identifier (remote address), never
     *                  {@code null}
     * @return true if the request is allowed, false if it should be rejected
     *         with HTTP 429
     */
    public boolean tryAcquire(String clientKey) {
        long now = System.currentTimeMillis();
        long windowStart = now - windowMs;
        Deque<Long> window = windows.computeIfAbsent(clientKey, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= windowStart) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * @return the configured limit
     */
    public int limit() {
        return limitPerMinute;
    }
}
