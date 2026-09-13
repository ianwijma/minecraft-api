package dev.example.mapi.internal;

import java.util.Map;

/**
 * Raw, loader-supplied block read, captured on the server thread under the
 * loaded-only chunk policy.
 *
 * @param blockId    registry id of the block ({@code namespace:path})
 * @param properties serialized block state properties (string values)
 * @param dimension  dimension the block was read from
 * @param x          block x
 * @param y          block y
 * @param z          block z
 */
public record RawBlockRead(
        String blockId,
        Map<String, String> properties,
        String dimension,
        int x,
        int y,
        int z) {
}
