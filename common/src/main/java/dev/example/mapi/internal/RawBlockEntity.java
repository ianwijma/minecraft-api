package dev.example.mapi.internal;

import java.util.Map;

/**
 * Raw, loader-supplied block-entity read, captured on the server thread.
 * The NBT payload uses the typed JSON convention (§7): byte {@code {"b":n}},
 * short {@code {"s":n}}, long {@code {"l":"n"}} (string), float
 * {@code {"f":n}}, arrays as {@code {"ba":[]}} / {@code {"ia":[]}} /
 * {@code {"la":["…"]}}, lists as {@code {"list":[]}}; ints, doubles,
 * strings, and compounds are JSON-native. Lossless: every tag type survives
 * a round trip.
 *
 * @param typeId    registry id of the block-entity type
 * @param dimension dimension the block entity was read from
 * @param x         block x
 * @param y         block y
 * @param z         block z
 * @param nbt       typed JSON payload of the full metadata save
 */
public record RawBlockEntity(
        String typeId,
        String dimension,
        int x,
        int y,
        int z,
        Map<String, Object> nbt) {
}
