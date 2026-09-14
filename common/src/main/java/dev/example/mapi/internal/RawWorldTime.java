package dev.example.mapi.internal;

/**
 * Raw, loader-supplied world clock read, captured on the server thread.
 *
 * @param gameTime           per-level game time (ticks)
 * @param overworldClockTime overworld clock value (26.2 time model)
 * @param defaultClockTime   the level's default clock value
 */
public record RawWorldTime(
        long gameTime,
        long overworldClockTime,
        long defaultClockTime) {
}
