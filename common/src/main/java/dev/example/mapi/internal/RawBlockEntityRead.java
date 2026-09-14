package dev.example.mapi.internal;

/**
 * Block-entity read outcome (spec §7: unknown data is explicit, never
 * empty).
 *
 * @param chunkLoaded false when the containing chunk is not loaded
 *                    (loaded-only policy)
 * @param entity      the read entity, or {@code null} when the chunk is
 *                    loaded but no block entity exists at the position
 */
public record RawBlockEntityRead(boolean chunkLoaded, RawBlockEntity entity) {

    /**
     * Outcome for an unloaded chunk.
     *
     * @return the shared unloaded outcome
     */
    public static RawBlockEntityRead unloaded() {
        return new RawBlockEntityRead(false, null);
    }

    /**
     * Outcome for a loaded chunk without a block entity.
     *
     * @return the shared absent outcome
     */
    public static RawBlockEntityRead absent() {
        return new RawBlockEntityRead(true, null);
    }
}
