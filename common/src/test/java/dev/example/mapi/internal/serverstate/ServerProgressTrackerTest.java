package dev.example.mapi.internal.serverstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.clock.ClockId;
import dev.example.mapi.internal.clock.ClockRegistry;
import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.event.EventFilter;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerProgressTrackerTest {

    private static TickObservation at(long tickCount, boolean frozen) {
        return new TickObservation(System.currentTimeMillis(), tickCount, frozen, false);
    }

    @Test
    void noDataYetMeansNoGate() {
        ServerProgressTracker tracker = new ServerProgressTracker(new ClockRegistry(), new EventBus());
        tracker.requireTickAdvancing();
        assertTrue(tracker.lastObservation().isEmpty());
    }

    @Test
    void advancingTicksKeepClocksGreen() {
        ClockRegistry clocks = new ClockRegistry();
        EventBus events = new EventBus();
        ServerProgressTracker tracker = new ServerProgressTracker(clocks, events);

        tracker.observe(at(10, false));
        tracker.observe(at(11, false));
        tracker.observe(at(12, false));

        var status = clocks.state(ClockId.SERVER_TICK);
        assertTrue(status.available());
        assertTrue(status.advancing());
        assertTrue(status.reason().isEmpty());
        assertEquals(12L, status.lastKnownBoundary().orElseThrow());
        tracker.requireTickAdvancing();
        assertTrue(!tracker.stalled());
        assertTrue(!tracker.lastObservation().orElseThrow().sprinting());
    }

    @Test
    void frozenTicksReportServerPaused() {
        ClockRegistry clocks = new ClockRegistry();
        ServerProgressTracker tracker = new ServerProgressTracker(clocks, new EventBus());
        tracker.observe(at(10, false));
        tracker.observe(at(10, true));

        var status = clocks.state(ClockId.SERVER_TICK);
        assertTrue(!status.advancing());
        assertEquals("tick-freeze", status.reason().orElseThrow());

        ProblemException e = assertThrows(ProblemException.class, tracker::requireTickAdvancing);
        assertEquals(ProblemCode.SERVER_PAUSED, e.code());
        assertEquals("tick-freeze", e.details().get("reason"));
        assertTrue(tracker.stalled());
    }

    @Test
    void stalledTickCountReportsClockNotAdvancing() {
        ClockRegistry clocks = new ClockRegistry();
        ServerProgressTracker tracker = new ServerProgressTracker(clocks, new EventBus());
        tracker.observe(at(10, false));
        tracker.observe(at(10, false));

        var status = clocks.state(ClockId.SERVER_TICK);
        assertTrue(!status.advancing());
        assertEquals("tick-count-stalled", status.reason().orElseThrow());

        ProblemException e = assertThrows(ProblemException.class, tracker::requireTickAdvancing);
        assertEquals(ProblemCode.CLOCK_NOT_ADVANCING, e.code());
        assertEquals("tick-count-stalled", e.details().get("reason"));
    }

    @Test
    void resumeAfterStallPublishesResumedEvent() {
        ClockRegistry clocks = new ClockRegistry();
        EventBus events = new EventBus();
        ServerProgressTracker tracker = new ServerProgressTracker(clocks, events);
        tracker.observe(at(10, false));
        tracker.observe(at(10, false));
        tracker.observe(at(11, false));

        var resumed = events.eventsAfter(0,
                new EventFilter(java.util.Set.of("server.tick.resumed"), Optional.empty()), 10);
        assertEquals(1, resumed.events().size());
        assertEquals(11L, resumed.events().get(0).payload().get("tickCount"));
        tracker.requireTickAdvancing();
        assertTrue(!tracker.stalled());
    }

    @Test
    void simulationStepClockMirrorsServerTick() {
        ClockRegistry clocks = new ClockRegistry();
        ServerProgressTracker tracker = new ServerProgressTracker(clocks, new EventBus());
        tracker.observe(at(7, false));
        assertEquals(clocks.state(ClockId.SERVER_TICK).advancing(),
                clocks.state(ClockId.SERVER_SIMULATION_STEP).advancing());
        assertEquals(7L, clocks.state(ClockId.SERVER_SIMULATION_STEP)
                .lastKnownBoundary().orElseThrow());
    }
}
