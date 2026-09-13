package dev.example.mapi.internal.snapshot;

import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.encoding.TagType;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.query.ServerThreadRunner;
import dev.example.mapi.internal.query.WorldQueryBackend;
import dev.example.mapi.internal.world.WorldLifecycleCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Captures bounded server-side observations at a tick boundary and retains
 * them (spec §12). The capture boundary is the server tick count read in the
 * same server-thread pass as the observation data, so boundary and content
 * cannot drift.
 */
public final class SnapshotCaptureService {

    private final SnapshotStore store;
    private final WorldQueryBackend queries;
    private final WorldLifecycleCoordinator world;
    private final ServerThreadRunner runner;

    /**
     * @param store   retention target
     * @param queries query backend for the observation content
     * @param world   world-session gate
     * @param runner  server-thread runner with bounded wait
     */
    public SnapshotCaptureService(SnapshotStore store, WorldQueryBackend queries,
            WorldLifecycleCoordinator world, ServerThreadRunner runner) {
        this.store = Objects.requireNonNull(store, "store");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.world = Objects.requireNonNull(world, "world");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Captures and retains a bounded observation.
     *
     * @param label       free-form capture label, never blank
     * @param maxPlayers  maximum players included, 0..100
     * @param maxEntities maximum entities included, 0..512
     * @return the retained snapshot id and boundary
     */
    public Captured capture(String label, int maxPlayers, int maxEntities) {
        if (label == null || label.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "label is required");
        }
        if (maxPlayers < 0 || maxPlayers > 100) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "maxPlayers must be 0..100");
        }
        if (maxEntities < 0 || maxEntities > 512) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "maxEntities must be 0..512");
        }
        String sessionId = world.requireActive(Optional.empty());
        Captured captured = call(() -> captureOnServerThread(label, maxPlayers, maxEntities, sessionId));
        store.retain(captured.snapshot());
        return captured;
    }

    /** @return the store this service retains into */
    public SnapshotStore store() {
        return store;
    }

    private Captured captureOnServerThread(String label, int maxPlayers, int maxEntities,
            String sessionId) {
        long boundary = queries.serverTickCount();
        Map<String, Tag> content = new LinkedHashMap<>();
        content.put("label", new Tag.StringTag(label));
        content.put("worldSessionId", new Tag.StringTag(sessionId));
        content.put("boundary", new Tag.LongTag(boundary));

        var players = queries.players(maxPlayers == 0 ? 1 : maxPlayers);
        Map<String, Tag> playerEntries = new LinkedHashMap<>();
        int playerCount = 0;
        for (WorldQueryBackend.PlayerRecord player : players) {
            if (playerCount++ >= maxPlayers) {
                break;
            }
            Map<String, Tag> p = new LinkedHashMap<>();
            p.put("name", new Tag.StringTag(player.name()));
            p.put("dimension", new Tag.StringTag(player.dimension()));
            p.put("x", new Tag.DoubleTag(player.x()));
            p.put("y", new Tag.DoubleTag(player.y()));
            p.put("z", new Tag.DoubleTag(player.z()));
            playerEntries.put(player.uuid(), new Tag.CompoundTag(p));
        }
        content.put("players", new Tag.CompoundTag(playerEntries));
        content.put("playerCount", new Tag.IntTag(TagType.INT, playerCount));

        var entities = queries.entities("minecraft:overworld", 0, 0, 0,
                128, maxEntities == 0 ? 1 : maxEntities);
        Map<String, Tag> entityEntries = new LinkedHashMap<>();
        int entityCount = 0;
        for (WorldQueryBackend.EntityRecord entity : entities) {
            if (entityCount++ >= maxEntities) {
                break;
            }
            Map<String, Tag> e = new LinkedHashMap<>();
            e.put("typeId", new Tag.StringTag(entity.typeId()));
            e.put("dimension", new Tag.StringTag(entity.dimension()));
            e.put("x", new Tag.DoubleTag(entity.x()));
            e.put("y", new Tag.DoubleTag(entity.y()));
            e.put("z", new Tag.DoubleTag(entity.z()));
            entityEntries.put(entity.uuid(), new Tag.CompoundTag(e));
        }
        content.put("entities", new Tag.CompoundTag(entityEntries));
        content.put("entityCount", new Tag.IntTag(TagType.INT, entityCount));

        Snapshot snapshot = new Snapshot("snap-" + System.currentTimeMillis() + "-"
                + Integer.toHexString(label.hashCode()), Optional.of(sessionId),
                System.currentTimeMillis(), Optional.of(boundary), new Tag.CompoundTag(content));
        return new Captured(snapshot.id(), boundary, snapshot);
    }

    private <T> T call(ServerThreadCall<T> task) {
        try {
            return runner.call(() -> {
                try {
                    return task.run();
                } catch (ProblemException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("snapshot capture failed", e);
                }
            });
        } catch (ProblemException e) {
            throw e;
        } catch (Exception e) {
            throw new ProblemException(ProblemCode.INTERNAL, "snapshot capture failed: " + e);
        }
    }

    @FunctionalInterface
    private interface ServerThreadCall<T> {
        T run() throws Exception;
    }

    /** Result of a capture: the retained id, boundary, and snapshot. */
    public record Captured(String id, long boundary, Snapshot snapshot) {

        /** @return the capture as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("snapshotId", id);
            map.put("worldSessionId", snapshot.worldSessionId().orElse(null));
            map.put("boundary", boundary);
            map.put("capturedAtEpochMs", snapshot.capturedAtEpochMs());
            return map;
        }
    }
}
