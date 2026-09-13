package dev.example.mapi.internal.discovery;

import dev.example.mapi.internal.json.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Writes and removes the local discovery file
 * {@code <gameDir>/mcapi/discovery.json} (spec schema version 1).
 *
 * <p>The discovery file lets local tools find the running instance without
 * guessing ports. It is written atomically (temp file + move) and contains
 * <strong>no secrets</strong>: the bearer token is never written to it and
 * readers must treat the content as untrusted data.
 */
public final class DiscoveryFile {

    /** Discovery schema version written by this class. */
    public static final int SCHEMA_VERSION = 1;

    /** File name inside the {@code mcapi} data directory. */
    public static final String FILE_NAME = "discovery.json";

    private DiscoveryFile() {
    }

    /**
     * Immutable snapshot of the values written to the discovery file.
     *
     * @param instanceId       sanitized instance identifier
     * @param processSessionId unique per process launch
     * @param pid              OS process id
     * @param startedAt        process start time (ISO-8601)
     * @param lastSeen         write time of this snapshot (ISO-8601)
     * @param readiness        current readiness state
     * @param physicalSide     physical side identifier
     * @param loader           loader identifier
     * @param mcVersion        Minecraft version
     * @param apiPort          loopback HTTP port
     * @param eventsPort       loopback WebSocket event port or {@code null}
     * @param labels           free-form labels (harness-supplied; may be empty)
     */
    public record DiscoverySnapshot(
            String instanceId,
            String processSessionId,
            long pid,
            Instant startedAt,
            Instant lastSeen,
            String readiness,
            String physicalSide,
            String loader,
            String mcVersion,
            int apiPort,
            Integer eventsPort,
            Map<String, String> labels) {
    }

    /**
     * Writes the discovery file for the given snapshot. Existing content is
     * replaced atomically when the filesystem supports it.
     *
     * @param dataDir the {@code mcapi} data directory
     * @param snapshot the values to write
     * @param logger platform logger; failures are logged, never thrown
     * @return true if the file is in place
     */
    public static boolean write(Path dataDir, DiscoverySnapshot snapshot, Logger logger) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("instanceId", snapshot.instanceId());
        body.put("processSessionId", snapshot.processSessionId());
        body.put("pid", snapshot.pid());
        body.put("startedAt", snapshot.startedAt().toString());
        body.put("lastSeen", snapshot.lastSeen().toString());
        body.put("readiness", snapshot.readiness());
        body.put("physicalSide", snapshot.physicalSide());
        body.put("loader", snapshot.loader());
        body.put("mcVersion", snapshot.mcVersion());
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("scheme", "http");
        api.put("host", "127.0.0.1");
        api.put("port", snapshot.apiPort());
        body.put("api", api);
        if (snapshot.eventsPort() != null) {
            Map<String, Object> events = new LinkedHashMap<>();
            events.put("scheme", "ws");
            events.put("host", "127.0.0.1");
            events.put("port", snapshot.eventsPort());
            body.put("events", events);
        }
        body.put("labels", snapshot.labels());

        Path target = dataDir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dataDir);
            restrictPermissions(dataDir, true);
            Path temp = dataDir.resolve(FILE_NAME + ".tmp-" + snapshot.pid());
            Files.writeString(temp, JsonWriter.write(body) + "\n", StandardCharsets.UTF_8);
            restrictPermissions(temp, false);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                restrictPermissions(target, false);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warn("MAPI: failed to write discovery file {}: {}", target, e.toString());
            return false;
        }
    }

    /**
     * Removes the discovery file and any leftover temp files. Best-effort:
     * failures are logged at debug level, never thrown.
     *
     * @param dataDir the {@code mcapi} data directory
     * @param logger platform logger
     */
    public static void delete(Path dataDir, Logger logger) {
        List<Path> leftovers = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, FILE_NAME + "*")) {
            stream.forEach(leftovers::add);
        } catch (IOException e) {
            logger.debug("MAPI: discovery cleanup skipped for {}: {}", dataDir, e.toString());
            return;
        }
        for (Path path : leftovers) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                logger.debug("MAPI: failed to remove {}: {}", path, e.toString());
            }
        }
    }

    private static void restrictPermissions(Path path, boolean directory) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                String permissions = directory ? "rwx------" : "rw-------";
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to restrict permissions on " + path, e);
        }
    }
}
