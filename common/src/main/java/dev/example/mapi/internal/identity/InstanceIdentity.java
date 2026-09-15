package dev.example.mapi.internal.identity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Identity surfaces published by a MAPI client instance (spec §7.2). The
 * runner assembles participant → process → connection → authoritative
 * player mapping from these values plus its own records; a client's claim
 * about a joined server is not itself proof of server identity.
 *
 * @param instanceId           stable per-mod-install identifier (generated
 *                             once and stored with the instance config)
 * @param bootId               fresh identifier for the current process boot
 */
public record InstanceIdentity(String instanceId, String bootId) {

    public InstanceIdentity {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(bootId, "bootId");
        if (instanceId.isBlank() || bootId.isBlank()) {
            throw new IllegalArgumentException("identity values must not be blank");
        }
    }

    /** @return a fresh identity with random UUIDs (used when no stored id exists) */
    public static InstanceIdentity generate() {
        return new InstanceIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    /** @return the identity as an ordered map for JSON serialization */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("instanceId", instanceId);
        map.put("bootId", bootId);
        return map;
    }
}
