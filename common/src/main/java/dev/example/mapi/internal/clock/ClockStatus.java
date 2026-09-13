package dev.example.mapi.internal.clock;

import java.util.Optional;

/**
 * Reported state of one clock (spec §4.1, §4.4). Availability and progress
 * are separate: a clock can be available but not advancing (paused, frozen),
 * and unavailable clocks never advance.
 *
 * @param clock             which clock, never {@code null}
 * @param available         true when this clock exists in the process (for
 *                          example client clocks are unavailable on a
 *                          dedicated server)
 * @param advancing         true when the clock is currently making progress
 * @param reason            when not advancing: the distinguishing cause
 *                          (for example {@code singleplayer-pause},
 *                          {@code tick-freeze}, {@code render-suspended},
 *                          {@code world-loading}, {@code thread-stalled})
 * @param lastKnownBoundary last observed boundary counter, if known
 * @param updatedAtEpochMs  wall-clock time of this report
 */
public record ClockStatus(
        ClockId clock,
        boolean available,
        boolean advancing,
        Optional<String> reason,
        Optional<Long> lastKnownBoundary,
        long updatedAtEpochMs) {

    public ClockStatus {
        clock = java.util.Objects.requireNonNull(clock, "clock");
        reason = reason == null ? Optional.empty() : reason;
        lastKnownBoundary = lastKnownBoundary == null ? Optional.empty() : lastKnownBoundary;
        if (advancing && reason.isPresent()) {
            throw new IllegalArgumentException("an advancing clock must not carry a hold reason");
        }
    }

    /** @return the status as an ordered map for JSON serialization */
    public java.util.Map<String, Object> toMap() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("clock", clock.wireName());
        map.put("available", available);
        map.put("advancing", advancing);
        reason.ifPresent(value -> map.put("reason", value));
        lastKnownBoundary.ifPresent(value -> map.put("lastKnownBoundary", value));
        map.put("updatedAtEpochMs", updatedAtEpochMs);
        return map;
    }
}
