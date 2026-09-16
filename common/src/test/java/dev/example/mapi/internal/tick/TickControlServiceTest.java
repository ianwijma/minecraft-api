package dev.example.mapi.internal.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.clock.ClockId;
import dev.example.mapi.internal.clock.ClockRegistry;
import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.lease.ControlLease;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.serverstate.ServerProgressTracker;
import dev.example.mapi.internal.snapshot.SnapshotStore;
import dev.example.mapi.internal.world.WorldLifecycleCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TickControlServiceTest {

    private final ClockRegistry clocks = new ClockRegistry();
    private final EventBus events = new EventBus();
    private final LeaseManager leases = new LeaseManager();
    private final ServerProgressTracker progress = new ServerProgressTracker(clocks, events);
    private final WorldLifecycleCoordinator world =
            new WorldLifecycleCoordinator(new dev.example.mapi.internal.job.JobManager(),
                    new SnapshotStore(1000, 8, dev.example.mapi.internal.encoding.EncodingLimits.DEFAULT),
                    leases, events);
    private final FakeBackend backend = new FakeBackend();
    private final TickControlService service =
            new TickControlService(backend, leases, progress, world, 1.0f, 100.0f);

    @AfterEach
    void tearDown() {
        leases.shutdown();
    }

    @Test
    void leaseOwnershipIsEnforced() {
        var state = service.state();
        assertTrue(!state.frozen());

        assertThrows(ProblemException.class, () -> service.freeze(null));
        assertEquals(ProblemCode.LEASE_REQUIRED,
                assertThrows(ProblemException.class, () -> service.freeze(null)).code());

        ControlLease lease = service.acquireLease("runner", 10_000);
        var after = service.freeze(lease);
        assertTrue(after.frozen());

        // A different owner cannot take the topic.
        assertThrows(ProblemException.class, () -> leases.acquire(TickControlService.LEASE_TOPIC, "other", 1000));
    }

    @Test
    void freezeAndUnfreezeReportStateAndProgress() {
        ControlLease lease = service.acquireLease("runner", 10_000);
        var frozen = service.freeze(lease);
        assertTrue(frozen.frozen());
        assertEquals("tick-freeze", clocks.state(ClockId.SERVER_TICK).reason().orElseThrow());
        assertThrows(ProblemException.class, progress::requireTickAdvancing);

        backend.tickCount += 5;
        var unfrozen = service.unfreeze(lease);
        assertTrue(!unfrozen.frozen());
        assertTrue(clocks.state(ClockId.SERVER_TICK).advancing());
    }

    @Test
    void rateBoundsAreEnforced() {
        ControlLease lease = service.acquireLease("runner", 10_000);
        assertEquals(20.0f, service.setTickRate(lease, 20.0f));
        ProblemException outOfBounds = assertThrows(ProblemException.class,
                () -> service.setTickRate(lease, 1000.0f));
        assertEquals(ProblemCode.BAD_REQUEST, outOfBounds.code());
        assertEquals(100.0f, outOfBounds.details().get("max"));
    }

    @Test
    void stepAndSprintValidateBounds() {
        ControlLease lease = service.acquireLease("runner", 10_000);
        var step = service.step(lease, 5);
        assertEquals(5, step.completed());
        var sprint = service.sprint(lease, 3);
        assertEquals(3, sprint.completed());
        assertThrows(ProblemException.class, () -> service.step(lease, 0));
        assertThrows(ProblemException.class, () -> service.step(lease, 10001));
        assertThrows(ProblemException.class, () -> service.sprint(lease, -1));
    }

    @Test
    void expiryRevokesControlAndOptionalRestoreHookIsConfigurable() {
        service.setRestoreOnLeaseExpiry(true);
        assertTrue(service.restoreOnLeaseExpiry());
        service.setRestoreOnLeaseExpiry(false);
        assertTrue(!service.restoreOnLeaseExpiry());
        assertEquals(Map.of("min", 1.0f, "max", 100.0f), service.rateBounds());
    }

    private static final class FakeBackend implements TickControlBackend {

        boolean frozen;
        boolean sprinting;
        float rate = 20.0f;
        long tickCount = 100;

        @Override
        public State state() {
            return new State(frozen, sprinting, rate, tickCount, Optional.empty());
        }

        @Override
        public boolean freeze() {
            frozen = true;
            return true;
        }

        @Override
        public boolean unfreeze() {
            frozen = false;
            return true;
        }

        @Override
        public Optional<Float> setTickRate(float newRate) {
            rate = newRate;
            return Optional.of(rate);
        }

        @Override
        public StepResult step(int ticks) {
            tickCount += ticks;
            return new StepResult(ticks, ticks, tickCount);
        }

        @Override
        public Optional<StepResult> sprint(int ticks) {
            sprinting = true;
            tickCount += ticks;
            sprinting = false;
            return Optional.of(new StepResult(ticks, ticks, tickCount));
        }

        @Override
        public boolean stopStepping() {
            return false;
        }

        @Override
        public boolean stopSprinting() {
            sprinting = false;
            return true;
        }

        @Override
        public boolean supportsStepping() {
            return true;
        }

        @Override
        public boolean supportsSprinting() {
            return true;
        }

        @Override
        public boolean supportsRate() {
            return true;
        }
    }
}
