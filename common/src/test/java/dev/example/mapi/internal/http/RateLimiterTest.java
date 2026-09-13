package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void allowsUpToLimitThenRejects() {
        RateLimiter limiter = new RateLimiter(3);
        assertTrue(limiter.tryAcquire("client-a"));
        assertTrue(limiter.tryAcquire("client-a"));
        assertTrue(limiter.tryAcquire("client-a"));
        assertFalse(limiter.tryAcquire("client-a"));
        assertTrue(limiter.tryAcquire("client-b"), "limits are per client");
    }

    @Test
    void windowResetsOverTime() throws Exception {
        RateLimiter limiter = new RateLimiter(1, 30);
        assertTrue(limiter.tryAcquire("client-a"));
        assertFalse(limiter.tryAcquire("client-a"));
        Thread.sleep(60);
        assertTrue(limiter.tryAcquire("client-a"), "window expiry allows new requests");
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(-1));
    }
}
