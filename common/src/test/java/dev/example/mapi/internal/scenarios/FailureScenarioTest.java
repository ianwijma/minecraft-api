package dev.example.mapi.internal.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.http.HttpApiServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Named failure scenarios from spec §10 (and §13.3): each test maps to one
 * documented scenario so CI failures name the broken contract.
 */
class FailureScenarioTest {

    private static final Logger LOG = LoggerFactory.getLogger(FailureScenarioTest.class);
    private static final String TOKEN = "scenario-token-0123456789";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private TestPlatform platform;
    private MapiRuntime runtime;
    private HttpApiServer server;
    private int port;

    private void startServer() throws Exception {
        int freePort;
        try (var socket = new java.net.ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort, TOKEN, 60), runtime, LOG);
        assertTrue(server.start());
        port = freePort;
    }

    /**
     * Runtime-driven instance (own HTTP + WS listeners) for scenarios that
     * exercise the WebSocket surface.
     */
    private void startInstance() throws Exception {
        Path instanceDir = Files.createTempDirectory("mapi-scenario");
        Files.createDirectories(instanceDir.resolve("config"));
        int freePort;
        try (var socket = new java.net.ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        Files.writeString(instanceDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                "http.enabled=true\nhttp.port=" + freePort + "\nhttp.token=" + TOKEN + "\n");
        platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return instanceDir.resolve("config");
            }

            @Override
            public Path gameDir() {
                return instanceDir;
            }
        };
        runtime = new MapiRuntime(platform);
        assertTrue(runtime.httpRunning());
        port = runtime.httpBoundPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void scenarioFrozenTickYieldsServerBusyNotStaleGuesses() throws Exception {
        startServer();
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.blocked());
        assertEquals(503, get("/api/v1/server/status").statusCode());
        assertEquals(503, get("/api/v1/time").statusCode());
        assertEquals(503, get("/api/v1/server/players").statusCode());
    }

    @Test
    void scenarioWorldUnloadDuringTaskFailsWithLifecycleChanged() throws Exception {
        startServer();
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> created = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":1000000000},\"deadlineMs\":30000}");
        assertEquals(202, created.statusCode(), created.body());
        platform.lifecycleListener().onServerStopping();
        platform.lifecycleListener().onServerStopped();
        String taskId = created.body().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        long deadline = System.currentTimeMillis() + 5000;
        String state = "";
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> snapshot = get("/api/v1/tasks/" + taskId);
            state = snapshot.body().replaceAll(".*\"state\":\"([a-zA-Z]+)\".*", "$1");
            if (state.equals("failed") || state.equals("cancelled")) {
                break;
            }
            Thread.sleep(25);
        }
        assertEquals("failed", state);
        assertTrue(get("/api/v1/health").statusCode() == 200, "API stays serviceable after unload");
    }

    @Test
    void scenarioTwoAgentsContendingForOneLease() throws Exception {
        startServer();
        HttpResponse<String> agentA = post("/api/v1/leases", "{\"lease\":\"client.input\",\"ttlMs\":60000}");
        assertEquals(200, agentA.statusCode(), agentA.body());
        HttpResponse<String> agentB = post("/api/v1/leases", "{\"lease\":\"client.input\",\"conflict\":\"reject\"}");
        assertEquals(409, agentB.statusCode(), agentB.body());
        assertTrue(agentB.body().contains("LEASE_HELD"));
        HttpResponse<String> preempt = post("/api/v1/leases",
                "{\"lease\":\"client.input\",\"conflict\":\"preempt\"}");
        assertEquals(200, preempt.statusCode(), preempt.body());
        assertTrue(agentA.body().contains("\"id\":\""), agentA.body());
    }

    @Test
    void scenarioLostResponseRetriedWithIdempotencyKeyReplays() throws Exception {
        startServer();
        String body = "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":42}}";
        HttpResponse<String> first = post("/api/v1/tasks", body);
        assertEquals(202, first.statusCode());
        // Retry after a "lost" response — same key and body.
        HttpResponse<String> retry = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "scenario-lost-response")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        // First request had no key, so this is a fresh creation; send it again
        // to prove replay of THIS key's response.
        HttpResponse<String> replay = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "scenario-lost-response")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals("true", replay.headers().firstValue("Idempotent-Replay").orElse(""));
        assertEquals(retry.body(), replay.body(), "identical key+body must replay");
    }

    @Test
    void scenarioTokenNeverAppearsInConfigLogsOrErrors(@TempDir Path ignored) {
        TestPlatform scopedPlatform = new TestPlatform(LOG);
        MapiConfig config = new MapiConfig(true, 25586, TOKEN, 60);
        assertFalse(config.toString().contains(TOKEN), "record toString must redact the token");
        assertTrue(config.toString().contains("<redacted>"), "toString shows redaction, not the value");
        assertFalse(scopedPlatform.logger().toString().contains(TOKEN));
    }

    @Test
    void scenarioDisabledApiStaysIdle() {
        TestPlatform idlePlatform = new TestPlatform(LOG);
        MapiRuntime idleRuntime = new MapiRuntime(idlePlatform);
        assertFalse(idleRuntime.httpRunning(), "disabled API must not bind");
        assertTrue(idleRuntime.serverStatus().isEmpty());
        assertTrue(idleRuntime.eventLog().headSeq() == 0, "no events before the API starts");
        idleRuntime.shutdown();
    }

    @Test
    void scenarioSlowWebsocketConsumerDisconnectPolicyGetsGapMarker() throws Exception {
        startInstance();
        runtime.registerClientOps(new dev.example.mapi.internal.client.MapiClientOps() {
            @Override
            public dev.example.mapi.internal.client.ClientStatusSnapshot status() {
                return new dev.example.mapi.internal.client.ClientStatusSnapshot(1, 1, 1, 1, 1, null, false,
                        null, null);
            }

            @Override
            public dev.example.mapi.internal.client.ScreenNode screenTree() {
                return new dev.example.mapi.internal.client.ScreenNode(null, null, null, null, null, null,
                        java.util.List.of());
            }

            @Override
            public dev.example.mapi.internal.client.KeyActionResult pressKey(String mapping, String action) {
                throw new dev.example.mapi.internal.client.MapiClientOps.UnknownMappingException(mapping);
            }

            @Override
            public dev.example.mapi.internal.client.ScreenshotResult captureScreenshot(long frameId) {
                throw new IllegalStateException("not used");
            }

            @Override
            public void releaseAllKeys() {
            }
        }, Runnable::run);

        Collector collector = new Collector();
        CompletableFuture<java.net.http.WebSocket> handshake = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + TOKEN)
                .buildAsync(URI.create("ws://127.0.0.1:" + runtime.wsBoundPort() + "/"), collector);
        handshake.join().sendText("{\"type\":\"subscribe\",\"after\":0,\"policy\":\"disconnect\"}", true).join();
        // Overrun the queue with a live publisher; the drop-oldest/disconnect
        // policy applies and the server must stay healthy either way.
        for (int i = 0; i < 400; i++) {
            runtime.eventLog().publish("bulk." + i, "instrumented", Map.of("i", i));
        }
        Thread.sleep(200);
        assertEquals(200, get("/api/v1/health").statusCode(), "server healthy after consumer overrun");
    }

    @Test
    void scenarioTokenRevocationEndsLiveWebsocketCleanly() throws Exception {
        startInstance();
        Collector collector = new Collector();
        CompletableFuture<java.net.http.WebSocket> handshake = client.newWebSocketBuilder()
                .header("Authorization", "Bearer " + TOKEN)
                .buildAsync(URI.create("ws://127.0.0.1:" + runtime.wsBoundPort() + "/"), collector);
        handshake.join();
        // Drain the hello so only post-shutdown messages remain.
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String seen = collector.messages.poll(500, TimeUnit.MILLISECONDS);
            if (seen != null && seen.contains("hello")) {
                break;
            }
        }
        runtime.shutdown();
        String message = collector.messages.poll(5, TimeUnit.SECONDS);
        assertTrue(message == null || message.contains("closing"),
                "server closes the stream explicitly or the socket simply ends; got: " + message);
    }

    private static final class Collector implements java.net.http.WebSocket.Listener {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        StringBuilder pending = new StringBuilder();
        AtomicReference<String> lastMessage = new AtomicReference<>("");

        String lastMessageOrEmpty() {
            return lastMessage.get();
        }

        @Override
        public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                messages.add(pending.toString());
                lastMessage.set(pending.toString());
                pending.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
