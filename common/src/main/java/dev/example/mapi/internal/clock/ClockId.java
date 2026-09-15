package dev.example.mapi.internal.clock;

/**
 * Named clocks of the timing contract (spec §4.1). Wall time is monotonic
 * process time; the others are game-progress boundaries. Clock availability
 * and progress are reported states, not guarantees.
 */
public enum ClockId {

    /** Monotonic elapsed time, independent of game progress. */
    WALL("wall"),
    /** Client update boundaries. */
    CLIENT_TICK("client-tick"),
    /** Rendered frame boundaries. */
    CLIENT_FRAME("client-frame"),
    /** Server tick-loop boundaries. */
    SERVER_TICK("server-tick"),
    /** Simulation advancement under the supported tick-control implementation. */
    SERVER_SIMULATION_STEP("server-simulation-step");

    private final String wireName;

    ClockId(String wireName) {
        this.wireName = wireName;
    }

    /** @return the exact string used on the wire, never {@code null} */
    public String wireName() {
        return wireName;
    }
}
