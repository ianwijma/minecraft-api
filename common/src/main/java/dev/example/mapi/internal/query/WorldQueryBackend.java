package dev.example.mapi.internal.query;

import dev.example.mapi.internal.encoding.Tag;
import java.util.List;
import java.util.Optional;

/**
 * Loader-neutral backend for bounded loaded-world queries (spec §20 server
 * section). Methods must be called on the server thread. Queries never
 * generate chunks; only loaded regions are observable. Unsupported
 * dimensions are reported, not guessed.
 */
public interface WorldQueryBackend {

    /**
     * One inventory slot entry (non-empty slots only).
     *
     * @param slot  container slot index
     * @param itemId namespaced item id
     * @param count stack count
     */
    record ItemRecord(int slot, String itemId, int count) {
    }

    /**
     * One online player.
     *
     * @param uuid      player UUID string
     * @param name      player name
     * @param dimension dimension identifier (for example
     *                  {@code minecraft:overworld})
     * @param x/y/z     position
     * @param inventory non-empty inventory slots
     */
    record PlayerRecord(String uuid, String name, String dimension,
            double x, double y, double z, List<ItemRecord> inventory) {
    }

    /**
     * One loaded entity in the queried region.
     *
     * @param uuid      entity UUID string
     * @param typeId    namespaced entity type id
     * @param dimension dimension identifier
     * @param x/y/z     position
     */
    record EntityRecord(String uuid, String typeId, String dimension,
            double x, double y, double z) {
    }

    /**
     * One block observation.
     *
     * @param blockId          namespaced block id (air included)
     * @param blockEntityTypeId block-entity type id when a block entity exists
     * @param data             typed NBT of the block entity when present
     */
    record BlockRecord(String blockId, Optional<String> blockEntityTypeId, Optional<Tag> data) {
    }

    /**
     * Registry summary.
     *
     * @param id   namespaced registry id (for example {@code minecraft:item})
     * @param size number of registered entries
     */
    record RegistrySummary(String id, int size) {
    }

    /**
     * @param max maximum players returned
     * @return online players with non-empty inventory slots, bounded
     */
    List<PlayerRecord> players(int max);

    /**
     * @param dimension dimension identifier
     * @param cx/cy/cz  region center
     * @param radius    region radius in blocks
     * @param max       maximum entities returned
     * @return loaded entities within the region, bounded
     */
    List<EntityRecord> entities(String dimension, double cx, double cy, double cz, int radius, int max);

    /**
     * @param dimension dimension identifier
     * @param x/y/z     block position
     * @return the block (and block entity when present)
     */
    Optional<BlockRecord> block(String dimension, int x, int y, int z);

    /** @return summaries of the built-in registries */
    List<RegistrySummary> registries();

    /**
     * @param registryId namespaced registry id
     * @param max        maximum entries returned
     * @return sorted namespaced entry ids, bounded
     */
    List<String> registryEntries(String registryId, int max);

    /** @return true when the dimension identifier is a loaded level */
    boolean canQueryDimension(String dimension);
}
