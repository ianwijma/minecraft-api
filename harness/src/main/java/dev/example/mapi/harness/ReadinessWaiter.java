package dev.example.mapi.harness;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Polls a discovery file until the instance reports the expected readiness
 * (or the timeout expires). Fixed-interval polling keeps behavior
 * predictable in CI; the discovery file doubles as the heartbeat, so a dead
 * process simply never satisfies the wait.
 */
public final class ReadinessWaiter {

    /** Default poll interval in ms. */
    public static final long DEFAULT_INTERVAL_MS = 500;

    private ReadinessWaiter() {
    }

    /**
     * Waits until {@code read.get()} yields a record whose readiness matches.
     *
     * @param read            discovery source (e.g. a game directory scanner)
     * @param expectedReadiness readiness value to wait for
     * @param timeoutMs       total budget in ms
     * @param intervalMs      poll interval in ms
     * @return the matching record, or empty on timeout
     */
    public static Optional<DiscoveryScanner.DiscoveryRecord> await(
            Supplier<Optional<DiscoveryScanner.DiscoveryRecord>> read,
            String expectedReadiness,
            long timeoutMs,
            long intervalMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() <= deadline) {
            try {
                Optional<DiscoveryScanner.DiscoveryRecord> record = read.get();
                if (record.isPresent() && record.get().readiness().equals(expectedReadiness)) {
                    return record;
                }
            } catch (RuntimeException ignored) {
                // Transient read problems (partial writes) retry on the next tick.
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Waits for a readiness state on the instance rooted at {@code gameDir}.
     *
     * @param gameDir          instance game directory
     * @param expectedReadiness readiness value to wait for
     * @param timeoutMs        total budget in ms
     * @return the matching record, or empty on timeout
     */
    public static Optional<DiscoveryScanner.DiscoveryRecord> awaitInGameDir(Path gameDir,
            String expectedReadiness, long timeoutMs) {
        return await(() -> DiscoveryScanner.scan(gameDir), expectedReadiness, timeoutMs, DEFAULT_INTERVAL_MS);
    }
}
