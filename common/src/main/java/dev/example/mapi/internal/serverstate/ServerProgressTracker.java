package dev.example.mapi.internal.serverstate;

import dev.example.mapi.internal.clock.ClockId;
import dev.example.mapi.internal.clock.ClockRegistry;
import dev.example.mapi.internal.clock.ClockStatus;
import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Tracks server tick progress from periodic observations and keeps the
 * {@code server-tick} / {@code server-simulation-step} clock states current
 * (spec §4.1, §4.4). Answers the "is simulation advancing" question with a
 * *reason* and never silently unpauses: frozen servers report
 * {@code SERVER_PAUSED}, stalled tick counters report
 * {@code CLOCK_NOT_ADVANCING}.
 */
public final class ServerProgressTracker {

    private final ClockRegistry clocks;
    private final EventBus events;
    private TickObservation last;
    private boolean lastTwoEqual;
    private boolean stalled;
    private boolean wasStalled;
    private long lastAdvanceAtEpochMs;

    /**
     * @param clocks clock registry to keep updated
     * @param events event bus for pause/resume notices
     */
    public ServerProgressTracker(ClockRegistry clocks, EventBus events) {
        this.clocks = Objects.requireNonNull(clocks, "clocks");
        this.events = Objects.requireNonNull(events, "events");
        this.lastAdvanceAtEpochMs = System.currentTimeMillis();
    }

    /**
     * Feeds one observation.
     *
     * @param observation the new observation, never {@code null}
     */
    public synchronized void observe(TickObservation observation) {
        Objects.requireNonNull(observation, "observation");
        TickObservation previous = last;
        boolean advanced = previous == null || previous.tickCount() != observation.tickCount();
        if (advanced) {
            lastAdvanceAtEpochMs = observation.observedAtEpochMs();
        }
        lastTwoEqual = previous != null && previous.tickCount() == observation.tickCount();
        last = observation;
        stalled = holdReason().isPresent();

        Optional<String> reason = holdReason();
        clocks.update(new ClockStatus(ClockId.SERVER_TICK, true, reason.isEmpty(), reason,
                Optional.of(observation.tickCount()), observation.observedAtEpochMs()));
        clocks.update(new ClockStatus(ClockId.SERVER_SIMULATION_STEP, true, reason.isEmpty(), reason,
                Optional.of(observation.tickCount()), observation.observedAtEpochMs()));

        if (previous != null && advanced && !stalled && wasStalled) {
            events.publish("server.tick.resumed", Optional.empty(),
                    Map.of("tickCount", observation.tickCount(),
                            "previousTickCount", previous.tickCount()));
        }
        wasStalled = stalled;
    }

    private synchronized Optional<String> holdReason() {
        if (last == null) {
            return Optional.empty();
        }
        if (last.tickFrozen()) {
            return Optional.of("tick-freeze");
        }
        if (lastTwoEqual) {
            return Optional.of("tick-count-stalled");
        }
        return Optional.empty();
    }

    /** @return the last observation, if any */
    public synchronized Optional<TickObservation> lastObservation() {
        return Optional.ofNullable(last);
    }

    /** @return wall-clock milliseconds since the last observed tick advance */
    public synchronized long msSinceLastAdvance() {
        return System.currentTimeMillis() - lastAdvanceAtEpochMs;
    }

    /** @return true when the tracker currently holds a stall/pause reason */
    public synchronized boolean stalled() {
        return stalled;
    }

    /**
     * Gate for waits that need simulation progress (spec §4.4). Fails with
     * {@code SERVER_PAUSED} for pause-like holds (tick freeze) and
     * {@code CLOCK_NOT_ADVANCING} otherwise.
     *
     * @throws ProblemException when the server tick loop is not advancing
     */
    public synchronized void requireTickAdvancing() {
        if (last == null) {
            return;
        }
        if (last.tickFrozen()) {
            throw new ProblemException(ProblemCode.SERVER_PAUSED, "server tick loop is frozen",
                    Map.of("reason", "tick-freeze", "tickCount", last.tickCount()));
        }
        if (lastTwoEqual) {
            throw new ProblemException(ProblemCode.CLOCK_NOT_ADVANCING,
                    "server tick loop is not advancing",
                    Map.of("reason", "tick-count-stalled", "tickCount", last.tickCount()));
        }
    }
}
