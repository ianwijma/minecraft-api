package dev.example.mapi.internal.query;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.world.WorldLifecycleCoordinator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded loaded-world queries behind the world-session gate (spec §20).
 * Read-only: no lease required, no chunk generation, results bounded and
 * JSON-ready.
 */
public final class WorldQueryService {

    /** Query bounds (spec §20: bounded observations). */
    public static final int MAX_PLAYERS = 100;
    public static final int MAX_ENTITIES = 512;
    public static final int MAX_RADIUS = 128;
    public static final int MAX_REGISTRY_ENTRIES = 1000;

    private final WorldQueryBackend backend;
    private final WorldLifecycleCoordinator world;
    private final ServerThreadRunner runner;

    /**
     * @param backend loader backend, never {@code null}
     * @param world   world-session gate, never {@code null}
     * @param runner  server-thread runner with bounded wait, never {@code null}
     */
    public WorldQueryService(WorldQueryBackend backend, WorldLifecycleCoordinator world,
            ServerThreadRunner runner) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.world = Objects.requireNonNull(world, "world");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * @param max maximum players, 1..{@link #MAX_PLAYERS}
     * @return online players
     */
    public List<Map<String, Object>> players(int max) {
        if (max < 1 || max > MAX_PLAYERS) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "max must be between 1 and " + MAX_PLAYERS);
        }
        world.requireActive(Optional.empty());
        List<WorldQueryBackend.PlayerRecord> records = call(() -> backend.players(max));
        return records.stream().map(WorldQueryService::playerToMap).toList();
    }

    /**
     * @param dimension dimension identifier
     * @param cx/cy/cz  region center
     * @param radius    radius in blocks, 1..{@link #MAX_RADIUS}
     * @param max       maximum entities, 1..{@link #MAX_ENTITIES}
     * @return loaded entities in the region
     */
    public List<Map<String, Object>> entities(String dimension, double cx, double cy, double cz,
            int radius, int max) {
        validateDimension(dimension);
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "radius must be between 1 and " + MAX_RADIUS);
        }
        if (max < 1 || max > MAX_ENTITIES) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "max must be between 1 and " + MAX_ENTITIES);
        }
        requireFinite(cx, cy, cz);
        world.requireActive(Optional.empty());
        if (!call(() -> backend.canQueryDimension(dimension))) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "dimension is not a loaded level", Map.of("dimension", dimension));
        }
        List<WorldQueryBackend.EntityRecord> records =
                call(() -> backend.entities(dimension, cx, cy, cz, radius, max));
        return records.stream().map(WorldQueryService::entityToMap).toList();
    }

    /**
     * @param dimension dimension identifier
     * @param x/y/z     block position
     * @return the block observation, or empty when the dimension is unknown
     */
    public Optional<Map<String, Object>> block(String dimension, int x, int y, int z) {
        validateDimension(dimension);
        validateCoordinate(x, y, z);
        world.requireActive(Optional.empty());
        if (!call(() -> backend.canQueryDimension(dimension))) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "dimension is not a loaded level", Map.of("dimension", dimension));
        }
        return call(() -> backend.block(dimension, x, y, z)).map(WorldQueryService::blockToMap);
    }

    /** @return built-in registry summaries */
    public List<Map<String, Object>> registries() {
        world.requireActive(Optional.empty());
        return call(backend::registries).stream()
                .map(r -> Map.<String, Object>of("id", r.id(), "size", r.size()))
                .toList();
    }

    /**
     * @param registryId namespaced registry id
     * @param max        maximum entries, 1..{@link #MAX_REGISTRY_ENTRIES}
     * @return sorted entry ids
     */
    public List<String> registryEntries(String registryId, int max) {
        if (registryId == null || registryId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "registryId is required");
        }
        if (max < 1 || max > MAX_REGISTRY_ENTRIES) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "max must be between 1 and " + MAX_REGISTRY_ENTRIES);
        }
        world.requireActive(Optional.empty());
        return call(() -> backend.registryEntries(registryId, max));
    }

    private <T> T call(ServerThreadCall<T> task) {
        try {
            return runner.call(() -> {
                try {
                    return task.run();
                } catch (ProblemException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("world query backend failed", e);
                }
            });
        } catch (ProblemException e) {
            throw e;
        } catch (IllegalStateException e) {
            throw new ProblemException(ProblemCode.INTERNAL, "world query failed: " + e.getMessage());
        } catch (Exception e) {
            throw new ProblemException(ProblemCode.INTERNAL, "world query failed: " + e);
        }
    }

    @FunctionalInterface
    private interface ServerThreadCall<T> {
        T run() throws Exception;
    }

    private static void validateDimension(String dimension) {
        if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "dimension must be a namespaced identifier");
        }
    }

    private static void validateCoordinate(int x, int y, int z) {
        if (Math.abs(x) > 30_000_000 || Math.abs(y) > 30_000_000 || Math.abs(z) > 30_000_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "coordinate out of range");
        }
    }

    private static void requireFinite(double cx, double cy, double cz) {
        if (!Double.isFinite(cx) || !Double.isFinite(cy) || !Double.isFinite(cz)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "coordinates must be finite");
        }
    }

    private static Map<String, Object> playerToMap(WorldQueryBackend.PlayerRecord p) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("uuid", p.uuid());
        map.put("name", p.name());
        map.put("dimension", p.dimension());
        map.put("x", p.x());
        map.put("y", p.y());
        map.put("z", p.z());
        map.put("inventory", p.inventory().stream().map(i -> Map.<String, Object>of(
                "slot", i.slot(), "itemId", i.itemId(), "count", i.count())).toList());
        return map;
    }

    private static Map<String, Object> entityToMap(WorldQueryBackend.EntityRecord e) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("uuid", e.uuid());
        map.put("typeId", e.typeId());
        map.put("dimension", e.dimension());
        map.put("x", e.x());
        map.put("y", e.y());
        map.put("z", e.z());
        return map;
    }

    private static Map<String, Object> blockToMap(WorldQueryBackend.BlockRecord b) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("blockId", b.blockId());
        b.blockEntityTypeId().ifPresent(value -> map.put("blockEntityTypeId", value));
        b.data().ifPresent(value -> map.put("data",
                dev.example.mapi.internal.encoding.TagJson.toWire(value)));
        return map;
    }
}
