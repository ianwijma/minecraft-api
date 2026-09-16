package dev.example.mapi.internal.tick;

import java.util.Optional;

/**
 * Loader-neutral backend for tick control (spec §5), implemented per loader
 * against the vanilla tick-rate manager. Methods must be called on the
 * server thread. Every method reports what it actually did; unsupported
 * operations return empty rather than pretending (spec §3.3, §5).
 */
public interface TickControlBackend {

    /** Current tick-control state snapshot. */
    record State(boolean frozen, boolean sprinting, float tickRate, long tickCount,
            Optional<Integer> sprintTicksRemaining) {
    }

    /** Result of a step/sprint request. */
    record StepResult(int requested, int completed, long boundaryTickCount) {
    }

    /** @return the current tick-control state, never {@code null} */
    State state();

    /** @return true when freezing succeeded */
    boolean freeze();

    /** @return true when unfreezing succeeded */
    boolean unfreeze();

    /**
     * @param rate new tick rate within configured bounds
     * @return the rate actually applied, empty when rate control is
     *     unsupported
     */
    Optional<Float> setTickRate(float rate);

    /**
     * Steps the simulation a bounded number of ticks. Only valid while
     * frozen (stepping advances a frozen server).
     *
     * @param ticks number of ticks to step, at least 1
     * @return requested vs completed counts and the completion boundary
     */
    StepResult step(int ticks);

    /**
     * Sprints the simulation a bounded number of ticks. Sprinting is
     * asynchronous: the backend schedules it and callers poll
     * {@link #state()} for completion ({@code sprinting} false again).
     *
     * @param ticks number of ticks to sprint, at least 1
     * @return result with the request recorded; {@code completed} is 0 until
     *     later observations confirm the sprint finished
     */
    Optional<StepResult> sprint(int ticks);

    /** @return true when active stepping was stopped */
    boolean stopStepping();

    /** @return true when active sprinting was stopped */
    boolean stopSprinting();

    /** @return true when stepping is supported by this backend */
    boolean supportsStepping();

    /** @return true when sprinting is supported by this backend */
    boolean supportsSprinting();

    /** @return true when rate control is supported by this backend */
    boolean supportsRate();
}
