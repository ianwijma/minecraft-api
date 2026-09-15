package dev.example.mapi.internal.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.job.JobManager;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.query.ServerThreadRunner;
import dev.example.mapi.internal.query.WorldQueryBackend;
import dev.example.mapi.internal.world.WorldLifecycleCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SnapshotCaptureServiceTest {

    private final EventBus events = new EventBus();
    private final JobManager jobs = new JobManager();
    private final LeaseManager leases = new LeaseManager();
    private final SnapshotStore store =
            new SnapshotStore(10_000, 8, dev.example.mapi.internal.encoding.EncodingLimits.DEFAULT);
    private final WorldLifecycleCoordinator world = new WorldLifecycleCoordinator(
            jobs, store, leases, events);

    private SnapshotCaptureService service(int maxPlayersBackend) {
        return new SnapshotCaptureService(store,
                new FakeQueryBackend(maxPlayersBackend), world, inlineRunner());
    }

    @AfterEach
    void tearDown() {
        jobs.shutdown();
        leases.shutdown();
    }

    @Test
    void captureRequiresActiveWorldAndValidBounds() {
        var service = service(5);
        assertThrows(ProblemException.class, () -> service.capture("test", 10, 10));
        world.beginLoad();
        assertThrows(ProblemException.class, () -> service.capture("test", 10, 10));
        world.activated();
        assertThrows(ProblemException.class, () -> service.capture(null, 1, 1));
        assertThrows(ProblemException.class, () -> service.capture("test", -1, 1));
        assertThrows(ProblemException.class, () -> service.capture("test", 101, 1));
        assertThrows(ProblemException.class, () -> service.capture("test", 1, 513));
    }

    @Test
    void captureRetainsSnapshotAtBoundary() {
        var service = service(5);
        world.beginLoad();
        world.activated();
        String sessionId = world.currentSessionId().orElseThrow();

        SnapshotCaptureService.Captured captured = service.capture("baseline", 10, 20);
        assertEquals(1234L, captured.boundary());
        assertEquals(sessionId, captured.snapshot().worldSessionId().orElseThrow());
        assertEquals("baseline", label(captured));
        assertEquals(1, store.size(System.currentTimeMillis()));

        // Content shape: label, boundary, player and entity compounds.
        var content = (dev.example.mapi.internal.encoding.Tag.CompoundTag) captured
                .snapshot().content();
        assertTrue(content.entries().containsKey("players"));
        assertTrue(content.entries().containsKey("entities"));
        assertTrue(content.entries().containsKey("boundary"));

        // The snapshot is diffable from the store.
        SnapshotStore.DiffResult selfDiff = store.diff(captured.id(), captured.id(),
                DiffOptions.DEFAULT, System.currentTimeMillis());
        assertEquals(0, selfDiff.records().size());
    }

    private static String label(SnapshotCaptureService.Captured captured) {
        var content = (dev.example.mapi.internal.encoding.Tag.CompoundTag) captured.snapshot().content();
        return ((dev.example.mapi.internal.encoding.Tag.StringTag) content.entries()
                .get("label")).value();
    }

    @Test
    void captureIsBounded() {
        var service = service(5);
        world.beginLoad();
        world.activated();
        var captured = service.capture("bounded", 1, 1);
        var content = (dev.example.mapi.internal.encoding.Tag.CompoundTag) captured.snapshot().content();
        assertEquals(1L, ((dev.example.mapi.internal.encoding.Tag.IntTag) content.entries()
                .get("playerCount")).value());
        assertEquals(1L, ((dev.example.mapi.internal.encoding.Tag.IntTag) content.entries()
                .get("entityCount")).value());
    }

    private static ServerThreadRunner inlineRunner() {
        return new ServerThreadRunner() {
            @Override
            public <T> T call(java.util.function.Supplier<T> task) {
                return task.get();
            }
        };
    }

    private static final class FakeQueryBackend implements WorldQueryBackend {

        private final int maxPlayersAvailable;

        FakeQueryBackend(int maxPlayersAvailable) {
            this.maxPlayersAvailable = maxPlayersAvailable;
        }

        @Override
        public List<PlayerRecord> players(int max) {
            List<PlayerRecord> records = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(max, maxPlayersAvailable); i++) {
                records.add(new PlayerRecord("uuid-" + i, "Player" + i, "minecraft:overworld",
                        i, 64, i, List.of()));
            }
            return records;
        }

        @Override
        public List<EntityRecord> entities(String dimension, double cx, double cy, double cz,
                int radius, int max) {
            List<EntityRecord> records = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(max, 3); i++) {
                records.add(new EntityRecord("ent-" + i, "minecraft:creeper", dimension,
                        cx + i, cy, cz));
            }
            return records;
        }

        @Override
        public Optional<BlockRecord> block(String dimension, int x, int y, int z) {
            return Optional.empty();
        }

        @Override
        public List<RegistrySummary> registries() {
            return List.of();
        }

        @Override
        public List<String> registryEntries(String registryId, int max) {
            return List.of();
        }

        @Override
        public long serverTickCount() {
            return 1234;
        }

        @Override
        public boolean canQueryDimension(String dimension) {
            return "minecraft:overworld".equals(dimension);
        }
    }
}
