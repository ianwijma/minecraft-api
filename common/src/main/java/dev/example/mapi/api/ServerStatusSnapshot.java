package dev.example.mapi.api;

import java.util.Optional;

/**
 * Read-only, immutable point-in-time snapshot of dedicated/integrated server
 * status, taken on the Minecraft server thread.
 *
 * <p>Instances are never mutated after creation and may be stored and shared
 * freely. Values are accurate for the moment of capture only; they are not
 * live views.
 *
 * <p>All numeric fields are clamped to be non-negative. {@code motd} is the
 * server's message-of-the-day rendered as plain text, or the empty string if
 * it is unavailable. Player identities, positions, chat, and file system
 * paths are deliberately not exposed.
 */
public record ServerStatusSnapshot(
        long capturedAtEpochMs,
        long startedAtEpochMs,
        int playerCount,
        int maxPlayers,
        long tickCount,
        double averageTickTimeMs,
        String motd) {

    /**
     * Creates a normalized snapshot. {@code motd == null} is normalized to the
     * empty string; negative numbers are clamped to zero.
     */
    public ServerStatusSnapshot {
        if (capturedAtEpochMs < 0L) {
            capturedAtEpochMs = 0L;
        }
        if (startedAtEpochMs < 0L) {
            startedAtEpochMs = 0L;
        }
        playerCount = Math.max(0, playerCount);
        maxPlayers = Math.max(0, maxPlayers);
        if (tickCount < 0L) {
            tickCount = 0L;
        }
        if (averageTickTimeMs < 0.0d || Double.isNaN(averageTickTimeMs)) {
            averageTickTimeMs = 0.0d;
        }
        motd = motd == null ? "" : motd;
    }

    /**
     * @return milliseconds between server start and snapshot capture, always
     *         non-negative
     */
    public long uptimeMs() {
        return Math.max(0L, capturedAtEpochMs - startedAtEpochMs);
    }
}
