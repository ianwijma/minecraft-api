package dev.example.mapi.internal.clock;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of reported clock states. Platform adapters update the states they
 * own (client clocks from client adapters, server clocks from server
 * adapters); consumers read a consistent snapshot. Wall time is always
 * available and advancing inside the process (spec §4.1).
 */
public final class ClockRegistry {

    private final Map<ClockId, ClockStatus> states = new EnumMap<>(ClockId.class);

    /** Creates the registry with wall advancing and all other clocks unreported. */
    public ClockRegistry() {
        for (ClockId clock : ClockId.values()) {
            states.put(clock, unreported(clock));
        }
        states.put(ClockId.WALL, new ClockStatus(ClockId.WALL, true, true,
                Optional.empty(), Optional.empty(), System.currentTimeMillis()));
    }

    private static ClockStatus unreported(ClockId clock) {
        return new ClockStatus(clock, false, false,
                Optional.of("not-reported-yet"), Optional.empty(), 0);
    }

    /**
     * Updates the reported state of one clock.
     *
     * @param status the new state, never {@code null}
     */
    public void update(ClockStatus status) {
        java.util.Objects.requireNonNull(status, "status");
        states.put(status.clock(), status);
    }

    /** @return the current state of every clock, in {@link ClockId} order */
    public List<ClockStatus> snapshot() {
        return List.copyOf(states.values());
    }

    /**
     * @param clock the clock to inspect
     * @return its current state, never {@code null}
     */
    public ClockStatus state(ClockId clock) {
        return states.get(clock);
    }

    /** @return wire-ready map of all clock states, in {@link ClockId} order */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("clocks", states.values().stream().map(ClockStatus::toMap).toList());
        return map;
    }
}
