package dev.example.mapi.internal;

import java.util.UUID;

/**
 * Raw, loader-supplied snapshot of one connected player, captured on the
 * server thread. Player identity exposure is part of the documented security
 * posture change (docs/security.md).
 */
public record RawPlayerSnapshot(
        String name,
        UUID id,
        String dimension,
        double x,
        double y,
        double z) {
}
