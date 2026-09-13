package dev.example.mapi.internal.snapshot;

import dev.example.mapi.internal.encoding.Tag;
import java.util.Optional;

/**
 * A retained bounded observation (spec §12). Content is a canonical typed
 * tree; the store enforces structural limits at capture time so oversized
 * observations are rejected before any game-thread work.
 *
 * @param id                stable snapshot identifier, never blank
 * @param worldSessionId    world the snapshot was taken in, if world-scoped
 * @param capturedAtEpochMs wall-clock capture time
 * @param boundary          tick/step boundary the snapshot was captured at,
 *                          if known
 * @param content           the bounded observation as a typed tree, never
 *                          {@code null}
 */
public record Snapshot(
        String id,
        Optional<String> worldSessionId,
        long capturedAtEpochMs,
        Optional<Long> boundary,
        Tag content) {

    public Snapshot {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("snapshot id must not be blank");
        }
        worldSessionId = worldSessionId == null ? Optional.empty() : worldSessionId;
        boundary = boundary == null ? Optional.empty() : boundary;
        content = java.util.Objects.requireNonNull(content, "content");
    }
}
