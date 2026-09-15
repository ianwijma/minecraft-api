package dev.example.mapi.internal.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.encoding.EncodingLimits;
import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.encoding.TagType;
import dev.example.mapi.internal.event.Event;
import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.event.EventFilter;
import dev.example.mapi.internal.job.JobManager;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.snapshot.Snapshot;
import dev.example.mapi.internal.snapshot.SnapshotStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldLifecycleCoordinatorTest {

    private final EventBus events = new EventBus();
    private final JobManager jobs = new JobManager();
    private final LeaseManager leases = new LeaseManager();
    private final SnapshotStore snapshots =
            new SnapshotStore(10_000, 16, EncodingLimits.DEFAULT);
    private final WorldLifecycleCoordinator coordinator =
            new WorldLifecycleCoordinator(jobs, snapshots, leases, events);

    @AfterEach
    void tearDown() {
        jobs.shutdown();
        leases.shutdown();
    }

    @Test
    void happyPathEmitsOrderedLifecycleEvents() {
        assertEquals(WorldPhase.NONE, coordinator.phase());
        String id = coordinator.beginLoad();
        assertEquals("world-1", id);
        assertEquals(WorldPhase.LOADING, coordinator.phase());

        coordinator.activated();
        assertEquals(WorldPhase.ACTIVE, coordinator.phase());
        assertEquals(Optional.of(id), coordinator.currentSessionId());

        WorldLifecycleCoordinator.UnloadReport report = coordinator.beginUnload();
        assertEquals(0, report.jobsTerminated());
        assertEquals(WorldPhase.UNLOADING, coordinator.phase());

        coordinator.unloaded();
        assertEquals(WorldPhase.NONE, coordinator.phase());
        assertTrue(coordinator.currentSessionId().isEmpty());

        List<Event> lifecycle = events.eventsAfter(0, EventFilter.any(), 100).events();
        assertEquals(List.of("world.loading", "world.loaded", "world.unloading", "world.unloaded"),
                lifecycle.stream().map(Event::type).toList());
        assertEquals(id, lifecycle.get(0).worldSessionId().orElseThrow());
    }

    @Test
    void secondLoadGetsAFreshSessionAndOldIdentityBecomesStale() {
        String first = coordinator.beginLoad();
        coordinator.activated();
        coordinator.beginUnload();
        coordinator.unloaded();
        String second = coordinator.beginLoad();
        coordinator.activated();
        assertTrue(!first.equals(second));

        // Presenting the old identity is stale; presenting the current one passes.
        assertEquals(second, coordinator.requireActive(Optional.of(second)));
        ProblemException stale = assertThrows(ProblemException.class,
                () -> coordinator.requireActive(Optional.of(first)));
        assertEquals(ProblemCode.STALE_WORLD, stale.code());
        assertEquals(first, stale.details().get("presented"));
    }

    @Test
    void requireActiveFailsWhenNoWorld() {
        ProblemException e = assertThrows(ProblemException.class,
                () -> coordinator.requireActive(Optional.empty()));
        assertEquals(ProblemCode.WORLD_NOT_LOADED, e.code());
        assertEquals(409, ProblemCode.WORLD_NOT_LOADED.httpStatus());

        coordinator.beginLoad();
        // LOADING is not ACTIVE: world-scoped operations must still fail.
        assertThrows(ProblemException.class, () -> coordinator.requireActive(Optional.empty()));
    }

    @Test
    void unloadTerminatesJobsRevokesWorldLeasesAndInvalidatesSnapshots() throws Exception {
        String id = coordinator.beginLoad();
        coordinator.activated();

        // Outstanding world-scoped job (cooperative so it is still running).
        var job = jobs.submit("test.world-job", Optional.of(id), 0, ctx -> {
            while (!ctx.isCancelled()) {
                Thread.sleep(10);
            }
            ctx.checkCancelled();
            return "never";
        });
        // World-scoped lease (topic convention world/<id>/<topic>).
        leases.acquire("world/" + id + "/input", "runner", 10_000);
        // World-scoped snapshot.
        snapshots.retain(new Snapshot("snap-w", Optional.of(id), System.currentTimeMillis(),
                Optional.empty(), world()));
        // Foreign work that must survive.
        snapshots.retain(new Snapshot("snap-other", Optional.of("world-other"),
                System.currentTimeMillis(), Optional.empty(), world()));
        leases.acquire("process/input", "runner", 10_000);

        WorldLifecycleCoordinator.UnloadReport report = coordinator.beginUnload();
        assertEquals(1, report.jobsTerminated());
        assertEquals(1, report.leasesRevoked());
        assertEquals(1, report.snapshotsInvalidated());

        var jobView = job.waitFor(2000).orElseThrow();
        assertEquals(dev.example.mapi.internal.job.JobState.CANCELLED, jobView.state());
        assertEquals(ProblemCode.WORLD_UNLOADED, jobView.failureCode().orElseThrow());

        assertTrue(leases.holderOf("world/" + id + "/input").isEmpty());
        assertTrue(leases.holderOf("process/input").isPresent());
        // World snapshot gone; foreign snapshot retained.
        assertThrows(ProblemException.class, () -> snapshots.get("snap-w", System.currentTimeMillis()));
        snapshots.get("snap-other", System.currentTimeMillis());
    }

    @Test
    void invalidTransitionsAreRefused() {
        assertThrows(IllegalStateException.class, coordinator::activated);
        assertThrows(IllegalStateException.class, coordinator::unloaded);

        coordinator.beginLoad();
        assertThrows(IllegalStateException.class, coordinator::beginLoad);

        // beginUnload from LOADING is allowed: servers may stop before start
        // completes.
        coordinator.beginUnload();
        assertThrows(IllegalStateException.class, coordinator::activated);
        coordinator.unloaded();
        assertThrows(IllegalStateException.class, coordinator::unloaded);
    }

    private static Tag world() {
        return new Tag.CompoundTag(Map.of("health",
                new Tag.IntTag(TagType.INT, 20)));
    }
}
