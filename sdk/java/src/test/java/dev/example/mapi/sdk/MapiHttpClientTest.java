package dev.example.mapi.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM SDK E2E tests against a live in-process MAPI instance via its
 * discovery + token files.
 */
class MapiHttpClientTest {

    private static final Logger LOG = LoggerFactory.getLogger(MapiHttpClientTest.class);
    private static final String TOKEN = "jvm-sdk-token-0123456789";

    private MapiRuntime runtime;
    private Path gameDir;

    private MapiHttpClient startInstance() throws Exception {
        gameDir = Files.createTempDirectory("mapi-sdk-java-test");
        Files.createDirectories(gameDir.resolve("config"));
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Files.writeString(gameDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                "http.enabled=true\nhttp.port=" + port + "\n");
        Files.createDirectories(gameDir.resolve("mcapi"));
        Files.writeString(gameDir.resolve("mcapi").resolve("token"), TOKEN + "\n");
        TestPlatform platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return gameDir.resolve("config");
            }

            @Override
            public Path gameDir() {
                return gameDir;
            }
        };
        runtime = new MapiRuntime(platform);
        assertTrue(runtime.httpRunning());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        return MapiHttpClient.fromGameDir(gameDir);
    }

    @AfterEach
    void stopInstance() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    @Test
    void discoveryResolutionAndReads() throws Exception {
        MapiHttpClient client = startInstance();
        assertEquals("ok", client.health().get("status"));
        assertEquals("worldReady", client.ready().get("readiness"));
        Map<String, Object> info = client.info();
        assertEquals("mapi", info.get("name"));
        assertTrue(info.get("scopes") instanceof List<?>);
        Map<String, Object> players = client.players(List.of("name"), 10, 0);
        assertEquals(2L, players.get("total"));
        Map<String, Object> time = client.worldTime("minecraft:overworld");
        assertEquals(12345L, time.get("gameTime"));
    }

    @Test
    void taskFlowWithIdempotencyAndEvents() throws Exception {
        MapiHttpClient client = startInstance();
        Map<String, Object> created = client.createTask("wait-for-tick", Map.of("targetTick", 42),
                null, "sdk-key-1", runtime.worldSessionId().orElseThrow());
        String taskId = String.valueOf(created.get("id"));
        Map<String, Object> done = client.waitTask(taskId, 5);
        assertEquals("succeeded", done.get("state"));

        Map<String, Object> replay = client.createTask("wait-for-tick", Map.of("targetTick", 42),
                null, "sdk-key-1", runtime.worldSessionId().orElseThrow());
        assertEquals(taskId, replay.get("id"), "identical key+body must replay the first task");

        Map<String, Object> outcome = client.executeCommand("say hi",
                runtime.worldSessionId().orElseThrow());
        assertEquals(Boolean.TRUE, outcome.get("success"));

        Map<String, Object> page = client.eventsAfter(0);
        assertTrue((Long) page.get("nextCursor") >= 1);
        assertTrue(((List<?>) page.get("events")).size() >= 1);

        MapiHttpClient.MapiException mismatch = assertThrows(MapiHttpClient.MapiException.class,
                () -> client.createTask("wait-for-tick", Map.of("targetTick", 43), null, "sdk-key-1", null));
        assertEquals(422, mismatch.status);
        assertEquals("IDEMPOTENCY_MISMATCH", mismatch.code);
    }

    @Test
    void missingDiscoveryFailsFast() throws Exception {
        Path empty = Files.createTempDirectory("mapi-sdk-empty");
        assertThrows(java.io.IOException.class, () -> MapiHttpClient.fromGameDir(empty));
    }
}
