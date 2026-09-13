package dev.example.mapi.harness;

import dev.example.mapi.internal.json.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and validates discovery files written by a running MAPI instance
 * ({@code <gameDir>/mcapi/discovery.json}, schema version 1). The content is
 * treated as <strong>untrusted data</strong>: unknown fields are ignored,
 * every field is type-checked, and identifiers are validated before use.
 *
 * <p>Staleness is decided by heartbeat age plus process identity, never by
 * PID alone (spec §9). Callers provide the maximum acceptable age; this
 * class only compares {@code lastSeen} against a caller-supplied clock.
 */
public final class DiscoveryScanner {

    /** Maximum acceptable values; guards against absurd/untrusted input. */
    public static final int MAX_ID_LENGTH = 64;

    /** Parsed discovery record. */
    public record DiscoveryRecord(
            String instanceId,
            String processSessionId,
            long pid,
            String startedAt,
            String lastSeen,
            String readiness,
            String physicalSide,
            String loader,
            String mcVersion,
            int apiPort,
            Integer eventsPort) {

        /**
         * @param maxAgeMs  maximum heartbeat age
         * @param nowMillis caller clock (epoch ms)
         * @return true when {@code lastSeen} is older than the budget
         */
        public boolean isStale(long maxAgeMs, long nowMillis) {
            return InstantParser.parse(lastSeen)
                    .map(instant -> nowMillis - instant > maxAgeMs)
                    .orElse(true);
        }
    }

    /** ISO-8601 instant parsing without a hard dependency order. */
    private static final class InstantParser {

        static Optional<Long> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(java.time.Instant.parse(value).toEpochMilli());
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
    }

    private DiscoveryScanner() {
    }

    /**
     * Reads and validates the discovery file for a game directory.
     *
     * @param gameDir the instance game directory
     * @return the record, or empty when absent or unreadable
     * @throws InvalidDiscoveryException when present but invalid
     */
    public static Optional<DiscoveryRecord> scan(Path gameDir) {
        Path file = gameDir.resolve("mcapi").resolve("discovery.json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
        return Optional.of(parse(text));
    }

    /**
     * Parses discovery JSON (schema 1). Unknown fields are ignored.
     *
     * @param json raw discovery file content
     * @return the validated record
     * @throws InvalidDiscoveryException on malformed or invalid content
     */
    public static DiscoveryRecord parse(String json) {
        Object parsed;
        try {
            parsed = JsonParser.parse(json);
        } catch (RuntimeException e) {
            throw new InvalidDiscoveryException("not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new InvalidDiscoveryException("discovery content must be a JSON object");
        }
        int schemaVersion = intField(root, "schemaVersion", true);
        if (schemaVersion != 1) {
            throw new InvalidDiscoveryException("unsupported schemaVersion: " + schemaVersion);
        }
        String instanceId = requireId(root, "instanceId");
        String processSessionId = requireId(root, "processSessionId");
        long pid = intField(root, "pid", true);
        String startedAt = requireString(root, "startedAt");
        String lastSeen = requireString(root, "lastSeen");
        String readiness = requireString(root, "readiness");
        if (!java.util.Set.of("http", "worldReady", "clientJoined").contains(readiness)) {
            throw new InvalidDiscoveryException("unknown readiness: " + readiness);
        }
        String physicalSide = requireString(root, "physicalSide");
        String loader = requireString(root, "loader");
        String mcVersion = requireString(root, "mcVersion");
        int apiPort = portField(root, "api");
        Integer eventsPort = null;
        if (root.get("events") instanceof Map<?, ?> events) {
            Object port = events.get("port");
            if (port instanceof Number number && number.longValue() >= 0
                    && number.longValue() <= 65535) {
                eventsPort = number.intValue();
            } else {
                throw new InvalidDiscoveryException("events.port must be a valid port");
            }
        }
        return new DiscoveryRecord(instanceId, processSessionId, pid, startedAt, lastSeen, readiness,
                physicalSide, loader, mcVersion, apiPort, eventsPort);
    }

    private static String requireId(Map<?, ?> root, String field) {
        String value = requireString(root, field);
        if (value.length() > MAX_ID_LENGTH || !value.matches("[a-zA-Z0-9._-]+")) {
            throw new InvalidDiscoveryException(field + " must be 1.." + MAX_ID_LENGTH
                    + " chars of [a-zA-Z0-9._-]: " + value);
        }
        return value;
    }

    private static String requireString(Map<?, ?> root, String field) {
        if (!(root.get(field) instanceof String value) || value.isBlank()) {
            throw new InvalidDiscoveryException(field + " must be a non-empty string");
        }
        return value;
    }

    private static int intField(Map<?, ?> root, String field, boolean required) {
        if (!(root.get(field) instanceof Number number)) {
            if (required) {
                throw new InvalidDiscoveryException(field + " must be a number");
            }
            return -1;
        }
        return number.intValue();
    }

    private static int portField(Map<?, ?> root, String field) {
        if (!(root.get(field) instanceof Map<?, ?> api)) {
            throw new InvalidDiscoveryException(field + " must be an object");
        }
        if (!(api.get("port") instanceof Number port) || port.longValue() < 1 || port.longValue() > 65535) {
            throw new InvalidDiscoveryException(field + ".port must be a valid port");
        }
        return port.intValue();
    }

    /** Discovery content was present but invalid (untrusted input rejected). */
    public static final class InvalidDiscoveryException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InvalidDiscoveryException(String message) {
            super(message);
        }
    }
}
