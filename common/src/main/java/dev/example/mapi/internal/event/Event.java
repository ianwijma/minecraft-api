package dev.example.mapi.internal.event;

import java.util.Map;
import java.util.Optional;

/**
 * A single published event with a monotonically increasing sequence number.
 * Sequence numbers define the total order and the cursor space for resumable
 * consumers (spec §13.2).
 *
 * @param seq            monotonically increasing sequence number, starting at 1
 * @param type           event type (namespaced, for example
 *                       {@code "world.unloaded"}), never blank
 * @param atEpochMs      wall-clock publish time
 * @param worldSessionId world the event belongs to, if world-scoped
 * @param payload        JSON-able structured payload, possibly empty
 */
public record Event(long seq, String type, long atEpochMs,
        Optional<String> worldSessionId, Map<String, Object> payload) {
}
