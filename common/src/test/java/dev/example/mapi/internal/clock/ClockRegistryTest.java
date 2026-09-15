package dev.example.mapi.internal.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClockRegistryTest {

    @Test
    void wallAdvancesByDefaultAndOthersAreUnreported() {
        ClockRegistry registry = new ClockRegistry();
        ClockStatus wall = registry.state(ClockId.WALL);
        assertTrue(wall.available());
        assertTrue(wall.advancing());
        assertTrue(wall.reason().isEmpty());

        for (ClockId clock : List.of(ClockId.CLIENT_TICK, ClockId.CLIENT_FRAME,
                ClockId.SERVER_TICK, ClockId.SERVER_SIMULATION_STEP)) {
            ClockStatus status = registry.state(clock);
            assertTrue(!status.available());
            assertTrue(!status.advancing());
            assertEquals("not-reported-yet", status.reason().orElseThrow());
        }
        assertEquals(5, registry.snapshot().size());
    }

    @Test
    void updateReplacesStateAndPreservesInvariants() {
        ClockRegistry registry = new ClockRegistry();
        registry.update(new ClockStatus(ClockId.SERVER_TICK, true, false,
                Optional.of("singleplayer-pause"), Optional.of(120L), System.currentTimeMillis()));
        ClockStatus paused = registry.state(ClockId.SERVER_TICK);
        assertTrue(paused.available());
        assertTrue(!paused.advancing());
        assertEquals("singleplayer-pause", paused.reason().orElseThrow());
        assertEquals(120L, paused.lastKnownBoundary().orElseThrow());

        registry.update(new ClockStatus(ClockId.SERVER_TICK, true, true,
                Optional.empty(), Optional.of(121L), System.currentTimeMillis()));
        assertTrue(registry.state(ClockId.SERVER_TICK).advancing());
    }

    @Test
    void advancingClockMustNotCarryHoldReason() {
        assertThrows(IllegalArgumentException.class, () -> new ClockStatus(
                ClockId.CLIENT_FRAME, true, true, Optional.of("paused"), Optional.empty(), 0));
    }

    @Test
    void wireMappingIsStable() {
        assertEquals("wall", ClockId.WALL.wireName());
        assertEquals("client-tick", ClockId.CLIENT_TICK.wireName());
        assertEquals("client-frame", ClockId.CLIENT_FRAME.wireName());
        assertEquals("server-tick", ClockId.SERVER_TICK.wireName());
        assertEquals("server-simulation-step", ClockId.SERVER_SIMULATION_STEP.wireName());

        ClockRegistry registry = new ClockRegistry();
        assertTrue(registry.toMap().get("clocks") instanceof List<?> clocks
                && clocks.size() == 5);
    }
}
