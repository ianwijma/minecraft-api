package dev.example.mapi.internal.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.job.JobManager;
import dev.example.mapi.internal.lease.LeaseManager;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.snapshot.SnapshotStore;
import dev.example.mapi.internal.world.WorldLifecycleCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldQueryServiceTest {

    private final EventBus events = new EventBus();
    private final JobManager jobs = new JobManager();
    private final LeaseManager leases = new LeaseManager();
    private final WorldLifecycleCoordinator world = new WorldLifecycleCoordinator(jobs,
            new SnapshotStore(1000, 8, dev.example.mapi.internal.encoding.EncodingLimits.DEFAULT),
            leases, events);
    private final WorldQueryService service = new WorldQueryService(
            new FakeBackend(), world, new ServerThreadRunner() {
                @Override
                public <T> T call(java.util.function.Supplier<T> task) {
                    return task.get();
                }
            });

    @AfterEach
    void tearDown() {
        jobs.shutdown();
        leases.shutdown();
    }

    @Test
    void queriesRequireAnActiveWorld() {
        ProblemException e = assertThrows(ProblemException.class, () -> service.players(10));
        assertEquals(ProblemCode.WORLD_NOT_LOADED, e.code());

        world.beginLoad();
        assertThrows(ProblemException.class, () -> service.players(10));
        world.activated();
        assertEquals(1, service.players(10).size());
    }

    @Test
    void boundsAreValidated() {
        world.beginLoad();
        world.activated();
        assertThrows(ProblemException.class, () -> service.players(0));
        assertThrows(ProblemException.class, () -> service.players(101));
        assertThrows(ProblemException.class, () -> service.entities("minecraft:overworld",
                0, 0, 0, 0, 10));
        assertThrows(ProblemException.class, () -> service.entities("minecraft:overworld",
                0, 0, 0, 129, 10));
        assertThrows(ProblemException.class, () -> service.entities("minecraft:overworld",
                0, 0, 0, 10, 513));
        assertThrows(ProblemException.class, () -> service.entities("minecraft:overworld",
                Double.NaN, 0, 0, 10, 10));
        assertThrows(ProblemException.class, () -> service.entities("bad-dimension",
                0, 0, 0, 10, 10));
        assertThrows(ProblemException.class, () -> service.block("minecraft:overworld",
                30_000_001, 0, 0));
        assertThrows(ProblemException.class, () -> service.registryEntries("minecraft:item", 0));
    }

    @Test
    void unknownDimensionsAreRejectedNotGuessed() {
        world.beginLoad();
        world.activated();
        ProblemException e = assertThrows(ProblemException.class,
                () -> service.entities("minecraft:the_end", 0, 0, 0, 10, 10));
        assertEquals(ProblemCode.BAD_REQUEST, e.code());
        assertEquals("minecraft:the_end", e.details().get("dimension"));
    }

    @Test
    void wireMapsCarveTheDocumentedShape() {
        world.beginLoad();
        world.activated();

        List<Map<String, Object>> players = service.players(10);
        assertEquals("00000000-0000-0000-0000-000000000001", players.get(0).get("uuid"));
        assertEquals("Zoe", players.get(0).get("name"));
        assertEquals("minecraft:overworld", players.get(0).get("dimension"));
        assertEquals(1.5, players.get(0).get("x"));
        var inventory = (List<?>) players.get(0).get("inventory");
        assertEquals(Map.of("slot", 0, "itemId", "minecraft:stone", "count", 64), inventory.get(0));

        List<Map<String, Object>> entities = service.entities("minecraft:overworld",
                0, 0, 0, 32, 10);
        assertEquals(1, entities.size());
        assertEquals("minecraft:creeper", entities.get(0).get("typeId"));

        Map<String, Object> block = service.block("minecraft:overworld", 1, 64, 2).orElseThrow();
        assertEquals("minecraft:furnace", block.get("blockId"));
        assertEquals("minecraft:furnace", block.get("blockEntityTypeId"));
        assertTrue(((String) block.toString()).contains("compound"));

        List<Map<String, Object>> registries = service.registries();
        assertEquals(2, registries.size());
        assertEquals(List.of("minecraft:apple", "minecraft:stone"),
                service.registryEntries("minecraft:item", 1000));
        assertEquals(List.of(), service.registryEntries("minecraft:missing", 1000));
    }

    private static final class FakeBackend implements WorldQueryBackend {

        @Override
        public List<PlayerRecord> players(int max) {
            return List.of(new PlayerRecord("00000000-0000-0000-0000-000000000001", "Zoe",
                    "minecraft:overworld", 1.5, 64, 2.5,
                    List.of(new ItemRecord(0, "minecraft:stone", 64))));
        }

        @Override
        public List<EntityRecord> entities(String dimension, double cx, double cy, double cz,
                int radius, int max) {
            return List.of(new EntityRecord("00000000-0000-0000-0000-0000000000ff",
                    "minecraft:creeper", dimension, cx + 1, cy, cz));
        }

        @Override
        public Optional<BlockRecord> block(String dimension, int x, int y, int z) {
            return Optional.of(new BlockRecord("minecraft:furnace",
                    Optional.of("minecraft:furnace"),
                    Optional.of(new dev.example.mapi.internal.encoding.Tag.CompoundTag(
                            Map.of("burn", new dev.example.mapi.internal.encoding.Tag.IntTag(
                                    dev.example.mapi.internal.encoding.TagType.INT, 1))))));
        }

        @Override
        public List<RegistrySummary> registries() {
            return List.of(new RegistrySummary("minecraft:item", 1500),
                    new RegistrySummary("minecraft:block", 2500));
        }

        @Override
        public List<String> registryEntries(String registryId, int max) {
            if ("minecraft:item".equals(registryId)) {
                return List.of("minecraft:apple", "minecraft:stone");
            }
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
