package dev.example.mapi.internal.serverstate;

/**
 * One server-thread observation feeding the progress tracker (spec §4.4).
 * Produced on the server thread by the snapshot path.
 *
 * @param observedAtEpochMs wall-clock time of the observation
 * @param tickCount         server tick counter at observation time
 * @param tickFrozen        true when the vanilla tick freeze is active
 * @param sprinting         true when the vanilla tick sprint is active
 */
public record TickObservation(long observedAtEpochMs, long tickCount, boolean tickFrozen, boolean sprinting) {
}
