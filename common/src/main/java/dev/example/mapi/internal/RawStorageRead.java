package dev.example.mapi.internal;

/**
 * Container storage read outcome (loaded-only policy).
 *
 * @param chunkLoaded false when the containing chunk is not loaded
 * @param entity      the block-entity type id when a block entity exists,
 *                    else {@code null}
 * @param storage     the container snapshot, or {@code null} when the block
 *                    entity is absent or not a container
 */
public record RawStorageRead(boolean chunkLoaded, String entity, RawStorageSnapshot storage) {

    /**
     * Outcome for an unloaded chunk.
     *
     * @return the shared unloaded outcome
     */
    public static RawStorageRead unloaded() {
        return new RawStorageRead(false, null, null);
    }

    /**
     * Outcome for a loaded chunk without a container.
     *
     * @param entity block-entity type id or {@code null} when no block
     *               entity exists
     * @return the not-a-container outcome
     */
    public static RawStorageRead notContainer(String entity) {
        return new RawStorageRead(true, entity, null);
    }
}
