package dev.example.mapi.runner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Participant mapping assembly (spec §7.2): the runner maintains
 * participant → client process → connection session → authoritative server
 * player, using only what the API publishes (instance identity from
 * {@code /api/v1/client} + {@code /api/v1/server/world}). The mapping basis
 * is always disclosed: a client's claim about a joined server is not proof of
 * server identity.
 */
final class ParticipantMapper {

    /** One participant row. */
    record Participant(String participantId, String instanceId, String bootId,
            String mappingBasis, String phase) {

        /** @return the row as an ordered map */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("participantId", participantId);
            map.put("instanceId", instanceId);
            map.put("bootId", bootId);
            map.put("mappingBasis", mappingBasis);
            map.put("phase", phase);
            return map;
        }
    }

    /**
     * Builds a participant row from the live endpoints.
     *
     * @param participantId runner-assigned identifier, never blank
     * @param client        the client-info response body (may lack fields)
     * @param world         the world response body
     * @return the participant row with a disclosed mapping basis
     */
    static Participant map(String participantId, Map<String, Object> client,
            Map<String, Object> world) {
        String instanceId = string(client, "bridgeId", "unknown-instance");
        String phase = string(world, "phase", "NONE");
        // The API publishes client-local identity and phase; a joined-server
        // identity is not yet observable through the current endpoints, so
        // the basis is unverified rather than claimed (spec §7.2).
        return new Participant(participantId, instanceId,
                string(client, "bootId", "n/a"), "unverified", phase);
    }

    /** @return the mapping table as an ordered map */
    static Map<String, Object> table(Iterable<Participant> participants) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mappingBasisLegend", "address-based | observed-both-apis | handshake-verified | unverified");
        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Participant participant : participants) {
            rows.add(participant.toMap());
        }
        out.put("participants", rows);
        return out;
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        return Optional.ofNullable(map.get(key)).map(Object::toString).orElse(fallback);
    }
}
