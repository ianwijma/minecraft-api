package dev.example.mapi.internal;

/**
 * Result of a bounded snapshot attempt.
 *
 * @param serverRunning true if a server instance is currently active
 * @param timedOut      true if the bounded snapshot wait expired
 * @param snapshot      the snapshot, or {@code null} when not available
 */
public record SnapshotResult(boolean serverRunning, boolean timedOut, dev.example.mapi.api.ServerStatusSnapshot snapshot) {

    public static SnapshotResult notRunning() {
        return new SnapshotResult(false, false, null);
    }

    public static SnapshotResult timedOutResult() {
        return new SnapshotResult(true, true, null);
    }

    public static SnapshotResult of(dev.example.mapi.api.ServerStatusSnapshot snapshot) {
        return new SnapshotResult(true, false, snapshot);
    }
}
