package dev.example.mapi.internal;

/**
 * Raw, loader-supplied server values captured on the server thread. This is
 * the seam between Minecraft-specific adapter code and the loader-neutral
 * snapshot type ({@code ServerStatusSnapshot}).
 *
 * <p>{@code motd} may be {@code null} (normalized later). {@code tickFrozen}
 * and {@code sprinting} reflect the vanilla tick-rate manager when the loader
 * exposes it; {@code false} otherwise (the capability is then not advertised).
 */
public record RawServerInfo(
        long startedAtEpochMs,
        int playerCount,
        int maxPlayers,
        long tickCount,
        double averageTickTimeMs,
        String motd,
        boolean tickFrozen,
        boolean sprinting) {

    /**
     * Backward-compatible shape without tick-state fields (tick freeze
     * detection unsupported).
     */
    public RawServerInfo(
            long startedAtEpochMs, int playerCount, int maxPlayers, long tickCount,
            double averageTickTimeMs, String motd) {
        this(startedAtEpochMs, playerCount, maxPlayers, tickCount, averageTickTimeMs, motd, false, false);
    }
}
