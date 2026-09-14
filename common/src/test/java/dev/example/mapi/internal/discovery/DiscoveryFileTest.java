package dev.example.mapi.internal.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.discovery.DiscoveryFile.DiscoverySnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DiscoveryFileTest {

    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryFileTest.class);

    @TempDir
    Path dataDir;

    private static DiscoverySnapshot snapshot(String instanceId) {
        return new DiscoverySnapshot(instanceId, "proc-session-1", 4242L,
                Instant.parse("2026-09-13T12:00:00Z"), Instant.parse("2026-09-13T12:01:00Z"),
                "worldReady", "client", "fabric", "26.2", 25586, 25587, Map.of());
    }

    private Path file() {
        return dataDir.resolve(DiscoveryFile.FILE_NAME);
    }

    @Test
    void writesSchemaVersionOneWithNoSecrets() throws IOException {
        assertTrue(DiscoveryFile.write(dataDir, snapshot("client-2"), LOG));
        String json = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"schemaVersion\":1,"), json);
        assertTrue(json.contains("\"instanceId\":\"client-2\""), json);
        assertTrue(json.contains("\"processSessionId\":\"proc-session-1\""), json);
        assertTrue(json.contains("\"pid\":4242"), json);
        assertTrue(json.contains("\"readiness\":\"worldReady\""), json);
        assertTrue(json.contains("\"physicalSide\":\"client\""), json);
        assertTrue(json.contains("\"loader\":\"fabric\""), json);
        assertTrue(json.contains("\"mcVersion\":\"26.2\""), json);
        assertTrue(json.contains("\"host\":\"127.0.0.1\""), json);
        assertTrue(json.contains("\"port\":25586"), json);
        assertTrue(json.contains("\"labels\":{}"), json);
        assertFalse(json.toLowerCase().contains("token"), "discovery must never contain token material");
        assertEquals(1, countFiles(), "temp files must not leak into the data dir");
    }

    @Test
    void rewriteReplacesContentAndKeepsDirectoryClean() throws IOException {
        assertTrue(DiscoveryFile.write(dataDir, snapshot("a"), LOG));
        assertTrue(DiscoveryFile.write(dataDir, snapshot("b"), LOG));
        String json = Files.readString(file(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"instanceId\":\"b\""), json);
        assertEquals(1, countFiles());
    }

    @Test
    void deleteRemovesDiscoveryAndTempFiles() throws IOException {
        assertTrue(DiscoveryFile.write(dataDir, snapshot("a"), LOG));
        Files.writeString(dataDir.resolve(DiscoveryFile.FILE_NAME + ".tmp-1"), "leftover",
                StandardCharsets.UTF_8);
        DiscoveryFile.delete(dataDir, LOG);
        assertFalse(Files.exists(file()));
        assertEquals(0, countFiles());
    }

    @Test
    void deleteWithoutFileIsSafe() {
        DiscoveryFile.delete(dataDir, LOG);
        assertTrue(true, "delete on a missing or empty dir must not throw");
    }

    private int countFiles() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir)) {
            int count = 0;
            for (Path ignored : stream) {
                count++;
            }
            return count;
        }
    }
}
