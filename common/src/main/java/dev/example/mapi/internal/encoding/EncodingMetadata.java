package dev.example.mapi.internal.encoding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Version metadata attached to encoded payloads (spec §11.4). A data version
 * is compatibility metadata, not a guarantee that arbitrary mod data can be
 * upgraded automatically; cross-version import must be explicitly supported
 * or rejected.
 *
 * @param encodingVersion      version of this encoding specification
 * @param minecraftVersion     Minecraft version string
 * @param dataVersion          Minecraft data version (world-upgrade metadata),
 *                             if known
 * @param registryFingerprint  fingerprint of the observed registry/mod set,
 *                             if computed
 * @param adapterSchemaVersion schema version of any registered adapter set
 */
public record EncodingMetadata(
        int encodingVersion,
        String minecraftVersion,
        Optional<Integer> dataVersion,
        Optional<String> registryFingerprint,
        Optional<String> adapterSchemaVersion) {

    /** Current encoding version produced by this implementation. */
    public static final int CURRENT_ENCODING_VERSION = 1;

    public EncodingMetadata {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (minecraftVersion.isBlank()) {
            throw new IllegalArgumentException("minecraftVersion must not be blank");
        }
        dataVersion = dataVersion == null ? Optional.empty() : dataVersion;
        registryFingerprint = registryFingerprint == null ? Optional.empty() : registryFingerprint;
        adapterSchemaVersion = adapterSchemaVersion == null ? Optional.empty() : adapterSchemaVersion;
        if (encodingVersion < 1) {
            throw new IllegalArgumentException("encodingVersion must be at least 1");
        }
    }

    /**
     * @param minecraftVersion the Minecraft version string
     * @return minimal metadata with the current encoding version
     */
    public static EncodingMetadata minimal(String minecraftVersion) {
        return new EncodingMetadata(CURRENT_ENCODING_VERSION, minecraftVersion,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** @return the metadata as an ordered map for JSON serialization */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("encodingVersion", encodingVersion);
        map.put("minecraftVersion", minecraftVersion);
        dataVersion.ifPresent(value -> map.put("dataVersion", value));
        registryFingerprint.ifPresent(value -> map.put("registryFingerprint", value));
        adapterSchemaVersion.ifPresent(value -> map.put("adapterSchemaVersion", value));
        return map;
    }
}
