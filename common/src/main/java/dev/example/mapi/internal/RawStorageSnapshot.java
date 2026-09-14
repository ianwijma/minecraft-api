package dev.example.mapi.internal;

import java.util.List;

/**
 * Raw storage snapshot of one container block entity (spec §6.2 storage
 * adapter, read side; units are native item counts).
 *
 * @param typeId  registry id of the block-entity type
 * @param slots   per-slot entries ({@code null} slots are dropped)
 * @param total   container size (slot count including empty slots)
 */
public record RawStorageSnapshot(String typeId, List<Slot> slots, int total) {

    /**
     * One occupied slot.
     *
     * @param slot  slot index
     * @param itemId registry id of the item
     * @param count stack size (native units)
     */
    public record Slot(int slot, String itemId, int count) {
    }
}
