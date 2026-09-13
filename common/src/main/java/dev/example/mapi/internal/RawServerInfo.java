package dev.example.mapi.internal;

/**
 * Raw, loader-supplied server values captured on the server thread. This is
 * the seam between Minecraft-specific adapter code and the loader-neutral
 * snapshot type ({@code ServerStatusSnapshot}).
 *
 * <p>{@code motd} may be {@code null} (normalized later).
 */
public record RawServerInfo(
        long startedAtEpochMs,
        int playerCount,
        int maxPlayers,
        long tickCount,
        double averageTickTimeMs,
        String motd) {
}
