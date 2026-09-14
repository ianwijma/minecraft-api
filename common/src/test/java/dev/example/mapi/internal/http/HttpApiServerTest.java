package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP contract and authentication tests against a real loopback listener.
 * No Minecraft classes are involved; the server handle is a test double.
 */
class HttpApiServerTest {

    private static final Logger LOG = LoggerFactory.getLogger(HttpApiServerTest.class);
    private static final String TOKEN = "test-token-0123456789";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private TestPlatform platform;
    private MapiRuntime runtime;
    private HttpApiServer server;
    private int port;

    private void startServer(MapiConfig config) {
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(config, runtime, LOG);
        assertTrue(server.start());
        port = config.httpPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private MapiConfig enabledConfig() throws Exception {
        return new MapiConfig(true, freePort(), TOKEN, 60);
    }

    private HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sends a raw HTTP request over a socket so restricted headers such as
     * {@code Host} can be controlled exactly.
     *
     * @return the full raw response (status line, headers, body)
     */
    private String rawRequest(String hostHeader, String authorization) throws Exception {
        StringBuilder request = new StringBuilder();
        request.append("GET /api/v1/health HTTP/1.1\r\n");
        request.append(hostHeader == null ? "" : "Host: " + hostHeader + "\r\n");
        if (authorization != null) {
            request.append("Authorization: ").append(authorization).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = in.readAllBytes();
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Authentication
    // ------------------------------------------------------------------

    @Test
    void requiresBearerTokenOnEveryEndpoint() throws Exception {
        startServer(enabledConfig());
        for (String path : new String[] {"/api/v1/health", "/api/v1/live", "/api/v1/ready", "/api/v1/time",
                "/api/v1/info", "/api/v1/server/status"}) {
            HttpResponse<String> noAuth = get(path);
            assertEquals(401, noAuth.statusCode(), path);
            assertEquals("Bearer realm=\"mapi\"", noAuth.headers().firstValue("WWW-Authenticate").orElse(""));
            HttpResponse<String> wrongAuth = get(path, "Authorization", "Bearer not-the-token-0123");
            assertEquals(401, wrongAuth.statusCode(), path);
            HttpResponse<String> wrongScheme = get(path, "Authorization", "Basic dXNlcjpwYXNz");
            assertEquals(401, wrongScheme.statusCode(), path);
            HttpResponse<String> ok = get(path, "Authorization", "Bearer " + TOKEN);
            assertEquals(200, ok.statusCode(), path);
        }
    }

    // ------------------------------------------------------------------
    // Response schemas
    // ------------------------------------------------------------------

    @Test
    void healthSchema() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> response = get("/api/v1/health", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(Optional.of("application/json; charset=utf-8"),
                response.headers().firstValue("Content-Type"));
        assertEquals(Optional.of("no-store"), response.headers().firstValue("Cache-Control"));
        assertEquals(Optional.of("1"), response.headers().firstValue("X-MAPI-Protocol-Version"));
    }

    @Test
    void infoSchema() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> response = get("/api/v1/info", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.startsWith("{\"protocolVersion\":1,\"name\":\"mapi\",\"version\":\"0.1.0\","
                + "\"apiVersion\":\"0.1.0\",\"minecraftVersion\":\"26.2\",\"platform\":\"fabric\","
                + "\"platformVersion\":\"test-loader\",\"instanceId\":\"mapi-" + port + "\","
                + "\"processSessionId\":\""), body);
        assertTrue(body.contains("\"physicalSide\":\"dedicatedServer\""), body);
        assertTrue(body.contains("\"availableLogicalSides\":[]"), body);
        assertFalse(body.contains("worldSessionId"), "no world session before a server starts");
    }

    @Test
    void infoIdentityTracksSessions() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> before = get("/api/v1/info", "Authorization", "Bearer " + TOKEN);
        assertTrue(before.body().contains("\"availableLogicalSides\":[]"), before.body());

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> during = get("/api/v1/info", "Authorization", "Bearer " + TOKEN);
        assertTrue(during.body().contains("\"worldSessionId\":\""), during.body());
        assertTrue(during.body().contains("\"availableLogicalSides\":[\"server\"]"), during.body());

        platform.lifecycleListener().onServerStopping();
        platform.lifecycleListener().onServerStopped();
        HttpResponse<String> after = get("/api/v1/info", "Authorization", "Bearer " + TOKEN);
        assertFalse(after.body().contains("worldSessionId"), after.body());
    }

    @Test
    void liveSchema() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> response = get("/api/v1/live", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("{\"protocolVersion\":1,\"live\":true}", response.body());
    }

    @Test
    void readyTracksWorldSessions() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> before = get("/api/v1/ready", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, before.statusCode(), before.body());
        assertEquals("{\"protocolVersion\":1,\"readiness\":\"http\","
                + "\"states\":{\"http\":true,\"worldReady\":false,\"clientJoined\":false}}", before.body());

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> during = get("/api/v1/ready", "Authorization", "Bearer " + TOKEN);
        assertEquals("{\"protocolVersion\":1,\"readiness\":\"worldReady\","
                + "\"states\":{\"http\":true,\"worldReady\":true,\"clientJoined\":false}}", during.body());
    }

    @Test
    void timeSchemaReportsServerTickAvailability() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> before = get("/api/v1/time", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, before.statusCode(), before.body());
        assertTrue(before.body().startsWith("{\"protocolVersion\":1,\"wallClock\":"), before.body());
        assertTrue(before.body().contains("\"monotonicNanos\":"), before.body());
        assertTrue(before.body().contains(
                "\"serverTick\":{\"available\":false,\"reason\":\"SERVER_NOT_RUNNING\"}"), before.body());

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> during = get("/api/v1/time", "Authorization", "Bearer " + TOKEN);
        assertTrue(during.body().contains("\"serverTick\":{\"available\":true,\"value\":42}"), during.body());
    }

    @Test
    void serverBusyReturns503WhenSnapshotTimesOut() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.blocked());
        HttpResponse<String> response = get("/api/v1/server/status", "Authorization", "Bearer " + TOKEN);
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("SERVER_BUSY"));
        HttpResponse<String> time = get("/api/v1/time", "Authorization", "Bearer " + TOKEN);
        assertEquals(503, time.statusCode());
        assertTrue(time.body().contains("SERVER_BUSY"));
        platform.lifecycleListener().onServerStopping();
        platform.lifecycleListener().onServerStopped();
    }

    @Test
    void statusReportsNotRunningBeforeServerStart() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> response = get("/api/v1/server/status", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, response.statusCode());
        assertEquals("{\"protocolVersion\":1,\"running\":false}", response.body());
    }

    @Test
    void statusReportsSnapshotWhenServerRuns() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> response = get("/api/v1/server/status", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().startsWith("{\"protocolVersion\":1,\"running\":true,"), response.body());
        assertTrue(response.body().contains("\"startedAtEpochMs\":1000"), response.body());
        assertTrue(response.body().contains("\"playerCount\":3"), response.body());
        assertTrue(response.body().contains("\"maxPlayers\":20"), response.body());
        assertTrue(response.body().contains("\"tickCount\":42"), response.body());
        assertTrue(response.body().contains("\"averageTickTimeMs\":1.0"), response.body());
        assertTrue(response.body().contains("\"motd\":\"A Test World\""), response.body());
        assertFalse(response.body().contains("playerNames"), response.body());
    }

    // ------------------------------------------------------------------
    // Request validation
    // ------------------------------------------------------------------

    @Test
    void rejectsUnexpectedHostHeader() throws Exception {
        startServer(enabledConfig());
        String good = rawRequest("127.0.0.1:" + port, "Bearer " + TOKEN);
        assertTrue(good.startsWith("HTTP/1.1 200"), good);
        String badHost = rawRequest("evil.example.com", "Bearer " + TOKEN);
        assertTrue(badHost.startsWith("HTTP/1.1 403"), badHost);
        assertTrue(badHost.contains("FORBIDDEN_HOST"), badHost);
        String missingHost = rawRequest(null, "Bearer " + TOKEN);
        assertTrue(missingHost.startsWith("HTTP/1.1 403"), missingHost);
    }

    @Test
    void rejectsUnexpectedOriginAndKeepsCorsDisabled() throws Exception {
        startServer(enabledConfig());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "http://evil.example.com")
                .GET().build();
        HttpResponse<String> badOrigin = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, badOrigin.statusCode());
        assertTrue(badOrigin.body().contains("FORBIDDEN_ORIGIN"));
        assertFalse(badOrigin.headers().firstValue("Access-Control-Allow-Origin").isPresent(),
                "CORS must stay disabled");
    }

    @Test
    void rejectsWrongMethodsAndUnknownPaths() throws Exception {
        startServer(enabledConfig());
        HttpRequest put = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("Authorization", "Bearer " + TOKEN)
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> putResponse = client.send(put, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, putResponse.statusCode());
        assertEquals(Optional.of("GET, POST, DELETE"), putResponse.headers().firstValue("Allow"));

        // POST is supported but only on /api/v1/tasks.
        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, postResponse.statusCode());
        assertTrue(postResponse.body().contains("NOT_FOUND"), postResponse.body());
        assertFalse(postResponse.headers().firstValue("X-MAPI-Request-Id").isEmpty(),
                "every response carries a request id");

        HttpResponse<String> unknown = get("/api/v1/nope", "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknown.statusCode());
        assertTrue(unknown.body().contains("NOT_FOUND"));

        HttpResponse<String> oldVersion = get("/api/v2/health", "Authorization", "Bearer " + TOKEN);
        assertEquals(404, oldVersion.statusCode());
    }

    @Test
    void rejectsOversizedBodies() throws Exception {
        startServer(enabledConfig());
        // GET with a declared Content-Length above the limit must be rejected
        // with 413 (declared size is validated before the body is read).
        StringBuilder request = new StringBuilder();
        request.append("GET /api/v1/health HTTP/1.1\r\n");
        request.append("Host: 127.0.0.1:").append(port).append("\r\n");
        request.append("Authorization: Bearer ").append(TOKEN).append("\r\n");
        request.append("Content-Length: ").append(HttpApiServer.MAX_BODY_BYTES + 1).append("\r\n");
        request.append("Connection: close\r\n\r\n");
        String response;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            out.write(new byte[HttpApiServer.MAX_BODY_BYTES + 1]);
            out.flush();
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(response.startsWith("HTTP/1.1 413"), response);
        assertTrue(response.contains("PAYLOAD_TOO_LARGE"), response);
    }

    @Test
    void rateLimitsPerClient() throws Exception {
        startServer(new MapiConfig(true, freePort(), TOKEN, 2));
        assertEquals(200, get("/api/v1/health", "Authorization", "Bearer " + TOKEN).statusCode());
        assertEquals(200, get("/api/v1/health", "Authorization", "Bearer " + TOKEN).statusCode());
        HttpResponse<String> third = get("/api/v1/health", "Authorization", "Bearer " + TOKEN);
        assertEquals(429, third.statusCode());
        assertTrue(third.headers().firstValue("Retry-After").isPresent());
    }

    // ------------------------------------------------------------------
    // Task protocol
    // ------------------------------------------------------------------

    private HttpResponse<String> post(String path, String body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String waitForTerminalState(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get("/api/v1/tasks/" + taskId, "Authorization", "Bearer " + TOKEN);
            assertEquals(200, response.statusCode(), response.body());
            String state = response.body().replaceAll(".*\"state\":\"([a-zA-Z]+)\".*", "$1");
            if (!state.equals("queued") && !state.equals("running") && !state.equals("cancelRequested")) {
                return state;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("task " + taskId + " never reached a terminal state");
    }

    @Test
    void taskLifecycleOverHttp() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> created = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":42}}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(202, created.statusCode(), created.body());
        assertTrue(created.headers().firstValue("Location").isPresent());
        assertTrue(created.body().contains("\"state\":\""), created.body());
        assertFalse(created.body().contains("\"error\""), created.body());
        String taskId = created.body().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");

        HttpResponse<String> listed = get("/api/v1/tasks?limit=10", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains(taskId), listed.body());

        assertEquals("succeeded", waitForTerminalState(taskId));

        HttpResponse<String> cancelResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(409, cancelResponse.statusCode(), cancelResponse.body());
        assertTrue(cancelResponse.body().contains("WRONG_STATE"), cancelResponse.body());
    }

    @Test
    void taskCancellationOverHttp() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> created = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":1000000000},\"deadlineMs\":10000}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(202, created.statusCode(), created.body());
        String taskId = created.body().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        Thread.sleep(150);
        HttpResponse<String> cancelResponse = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/tasks/" + taskId))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancelResponse.statusCode(), cancelResponse.body());
        String state = waitForTerminalState(taskId);
        assertEquals("cancelled", state);
    }

    @Test
    void idempotencyReplaysAndRejectsMismatchedBodies() throws Exception {
        startServer(enabledConfig());
        String body = "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":42}}";
        HttpResponse<String> first = post("/api/v1/tasks", body,
                "Authorization", "Bearer " + TOKEN, "Idempotency-Key", "key-1");
        assertEquals(202, first.statusCode(), first.body());
        HttpResponse<String> replay = post("/api/v1/tasks", body,
                "Authorization", "Bearer " + TOKEN, "Idempotency-Key", "key-1");
        assertEquals(202, replay.statusCode(), replay.body());
        assertEquals(Optional.of("true"), replay.headers().firstValue("Idempotent-Replay"));
        assertEquals(first.body(), replay.body(), "identical key+body must replay the first response");

        HttpResponse<String> mismatch = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":43}}",
                "Authorization", "Bearer " + TOKEN, "Idempotency-Key", "key-1");
        assertEquals(422, mismatch.statusCode(), mismatch.body());
        assertTrue(mismatch.body().contains("IDEMPOTENCY_MISMATCH"), mismatch.body());
    }

    @Test
    void taskValidationErrorsOverHttp() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> unknownKind = post("/api/v1/tasks", "{\"kind\":\"nope\",\"payload\":{}}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, unknownKind.statusCode(), unknownKind.body());
        assertTrue(unknownKind.body().contains("UNSUPPORTED"), unknownKind.body());

        HttpResponse<String> invalidJson = post("/api/v1/tasks", "{not json",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, invalidJson.statusCode(), invalidJson.body());
        assertTrue(invalidJson.body().contains("INVALID_JSON"), invalidJson.body());

        HttpResponse<String> missingKind = post("/api/v1/tasks", "{\"payload\":{}}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, missingKind.statusCode(), missingKind.body());
        assertTrue(missingKind.body().contains("INVALID_PAYLOAD"), missingKind.body());

        HttpResponse<String> unknownTask = get("/api/v1/tasks/00000000-0000-0000-0000-000000000000",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknownTask.statusCode());
        assertTrue(unknownTask.body().contains("NOT_FOUND"), unknownTask.body());
        assertTrue(unknownTask.body().contains("requestId"), unknownTask.body());
    }

    @Test
    void mutationRequiresAuthentication() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> response = post("/api/v1/tasks", "{\"kind\":\"wait-for-tick\"}");
        assertEquals(401, response.statusCode());
    }

    // ------------------------------------------------------------------
    // Server reads (players / block / time)
    // ------------------------------------------------------------------

    @Test
    void playersEndpointSupportsPaginationAndFieldSelection() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());

        HttpResponse<String> all = get("/api/v1/server/players", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains("\"name\":\"Asha\""), all.body());
        assertTrue(all.body().contains("\"total\":2"), all.body());
        assertTrue(all.body().contains("\"dataVersion\":4189"), all.body());

        HttpResponse<String> paged = get("/api/v1/server/players?limit=1&offset=1&fields=name,id",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, paged.statusCode(), paged.body());
        assertTrue(paged.body().contains("\"name\":\"Bram\""), paged.body());
        assertFalse(paged.body().contains("dimension"), paged.body());
        assertTrue(paged.body().contains("\"truncated\":false"), paged.body());

        HttpResponse<String> badField = get("/api/v1/server/players?fields=secret",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, badField.statusCode(), badField.body());
        assertTrue(badField.body().contains("INVALID_QUERY"), badField.body());
    }

    @Test
    void blockEndpointReportsLoadedPolicyAndErrors() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());

        HttpResponse<String> ok = get("/api/v1/server/world/block?dimension=minecraft:overworld&x=0&y=-64&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"blockId\":\"minecraft:stone\""), ok.body());
        assertTrue(ok.body().contains("\"policy\":\"loadedOnly\""), ok.body());

        HttpResponse<String> unknownDim = get(
                "/api/v1/server/world/block?dimension=minecraft:nowhere&x=0&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknownDim.statusCode(), unknownDim.body());
        assertTrue(unknownDim.body().contains("DIMENSION_NOT_FOUND"), unknownDim.body());

        HttpResponse<String> missingParams = get("/api/v1/server/world/block?dimension=minecraft:overworld",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, missingParams.statusCode(), missingParams.body());
        assertTrue(missingParams.body().contains("INVALID_QUERY"), missingParams.body());
    }

    @Test
    void worldTimeEndpointReportsClocks() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> ok = get("/api/v1/server/world/time?dimension=minecraft:overworld",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"gameTime\":12345"), ok.body());
        assertTrue(ok.body().contains("\"overworldClockTime\":6000"), ok.body());

        HttpResponse<String> unknownDim = get("/api/v1/server/world/time?dimension=minecraft:nowhere",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknownDim.statusCode(), unknownDim.body());
        assertTrue(unknownDim.body().contains("DIMENSION_NOT_FOUND"), unknownDim.body());
    }

    @Test
    void serverReadsRequireActiveSessionAndReturn409WithoutOne() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> players = get("/api/v1/server/players", "Authorization", "Bearer " + TOKEN);
        assertEquals(409, players.statusCode(), players.body());
        assertTrue(players.body().contains("WRONG_STATE"), players.body());
    }

    // ------------------------------------------------------------------
    // Scopes (spec §4.2)
    // ------------------------------------------------------------------

    private MapiConfig scopedConfig(java.util.List<String> scopes) throws Exception {
        int port = freePort();
        return new MapiConfig(true, port, TOKEN, 60, "mapi-" + port, null, 0, false,
                MapiConfig.DEFAULT_DISCOVERY_HEARTBEAT_SECONDS,
                java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(scopes)),
                MapiConfig.DEFAULT_COMMAND_PERMISSION_LEVEL, false, false);
    }

    @Test
    void scopeRestrictionsAuthorizeTheEffect() throws Exception {
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());

        HttpResponse<String> okObserve = get("/api/v1/server/players", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, okObserve.statusCode(), okObserve.body());

        HttpResponse<String> forbiddenRead = get(
                "/api/v1/server/world/block?dimension=minecraft:overworld&x=0&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbiddenRead.statusCode(), forbiddenRead.body());
        assertTrue(forbiddenRead.body().contains("FORBIDDEN_SCOPE"), forbiddenRead.body());
        assertTrue(forbiddenRead.body().contains("\"required\":\"world.read\""), forbiddenRead.body());

        HttpResponse<String> forbiddenControl = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"press\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbiddenControl.statusCode(), forbiddenControl.body());
        assertTrue(forbiddenControl.body().contains("\"required\":\"client.control\""), forbiddenControl.body());
    }

    @Test
    void worldReadScopeUnlocksWorldEndpoints() throws Exception {
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE,
                dev.example.mapi.internal.auth.Scope.WORLD_READ)));
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> ok = get("/api/v1/server/world/time?dimension=minecraft:overworld",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
    }

    @Test
    void infoCarriesTokenScopes() throws Exception {
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE,
                dev.example.mapi.internal.auth.Scope.WORLD_READ)));
        HttpResponse<String> info = get("/api/v1/info", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, info.statusCode(), info.body());
        assertTrue(info.body().contains("\"scopes\":[\"observe\",\"world.read\"]"), info.body());
    }

    // ------------------------------------------------------------------
    // Client reads (slice 0.6)
    // ------------------------------------------------------------------

    private void registerFakeClientOps() {
        runtime.registerClientOps(
                new dev.example.mapi.internal.client.MapiClientOps() {
                    @Override
                    public dev.example.mapi.internal.client.ClientStatusSnapshot status() {
                        return new dev.example.mapi.internal.client.ClientStatusSnapshot(1280, 720, 640, 360,
                                2, "TitleScreen", false, null, null);
                    }

                    @Override
                    public dev.example.mapi.internal.client.ScreenNode screenTree() {
                        return new dev.example.mapi.internal.client.ScreenNode("TitleScreen", null, 0, 0, 640,
                                360, java.util.List.of(new dev.example.mapi.internal.client.ScreenNode("Button",
                                        "Singleplayer", 100, 60, 200, 20, java.util.List.of())));
                    }

                    @Override
                    public dev.example.mapi.internal.client.KeyActionResult pressKey(String mapping,
                            String action) {
                        if (mapping.equals("key.forward")) {
                            if (!action.equals("press") && !action.equals("release") && !action.equals("tap")) {
                                throw new IllegalArgumentException(
                                        "action must be press, release, or tap: " + action);
                            }
                            boolean pressed = action.equals("press");
                            return new dev.example.mapi.internal.client.KeyActionResult(mapping, action,
                                    action.equals("tap") ? null : pressed);
                        }
                        throw new dev.example.mapi.internal.client.MapiClientOps.UnknownMappingException(
                                "mapping not reported by this client: " + mapping);
                    }

                    @Override
                    public void captureScreenshot(long frameId,
                            java.util.function.BiConsumer<dev.example.mapi.internal.client.ScreenshotResult,
                                    Exception> onComplete) {
                        onComplete.accept(new dev.example.mapi.internal.client.ScreenshotResult(frameId,
                                "mcapi/screenshots/frame-" + frameId + ".png", 1280, 720, 4242), null);
                    }

                    @Override
                    public void releaseAllKeys() {
                        // tracked in tests via releasedKeys counter
                        releasedKeys.incrementAndGet();
                    }

                    @Override
                    public boolean clickScreen(int x, int y) {
                        return x == 42 && y == 7;
                    }
                },
                Runnable::run);
    }

    private final java.util.concurrent.atomic.AtomicInteger releasedKeys =
            new java.util.concurrent.atomic.AtomicInteger();

    @Test
    void clientEndpointsRequireRegisteredOpsAndReportSnapshots() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> unregistered = get("/api/v1/client/status", "Authorization", "Bearer " + TOKEN);
        assertEquals(409, unregistered.statusCode(), unregistered.body());
        assertTrue(unregistered.body().contains("WRONG_STATE"), unregistered.body());

        registerFakeClientOps();

        HttpResponse<String> status = get("/api/v1/client/status", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, status.statusCode(), status.body());
        assertTrue(status.body().contains("\"window\":{\"width\":1280,\"height\":720}"), status.body());
        assertTrue(status.body().contains("\"currentScreenClass\":\"TitleScreen\""), status.body());
        assertTrue(status.body().contains("\"playerPresent\":false"), status.body());

        HttpResponse<String> tree = get("/api/v1/client/screen/tree", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, tree.statusCode(), tree.body());
        assertTrue(tree.body().contains("\"coverage\":\"best-effort\""), tree.body());
        assertTrue(tree.body().contains("\"label\":\"Singleplayer\""), tree.body());
        assertTrue(tree.body().contains("\"children\":[{"), tree.body());
    }

    @Test
    void clientInputKeyContract() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> unregistered = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"press\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(409, unregistered.statusCode(), unregistered.body());

        registerFakeClientOps();

        HttpResponse<String> ok = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"press\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"mode\":\"input\""), ok.body());
        assertTrue(ok.body().contains("\"isDown\":true"), ok.body());

        HttpResponse<String> wrongMode = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"press\",\"mode\":\"admin\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, wrongMode.statusCode(), wrongMode.body());
        assertTrue(wrongMode.body().contains("INVALID_PAYLOAD"), wrongMode.body());

        HttpResponse<String> unknownMapping = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.doesNotExist\",\"action\":\"press\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, unknownMapping.statusCode(), unknownMapping.body());
        assertTrue(unknownMapping.body().contains("INVALID_PAYLOAD"), unknownMapping.body());

        HttpResponse<String> unknownAction = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"wiggle\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, unknownAction.statusCode(), unknownAction.body());
    }

    @Test
    void clientScreenClickContract() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> unregistered = post("/api/v1/client/screen/click",
                "{\"x\":1,\"y\":2}", "Authorization", "Bearer " + TOKEN);
        assertEquals(409, unregistered.statusCode(), unregistered.body());

        registerFakeClientOps();
        HttpResponse<String> consumed = post("/api/v1/client/screen/click",
                "{\"x\":42,\"y\":7}", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, consumed.statusCode(), consumed.body());
        assertTrue(consumed.body().contains("\"mode\":\"semantic\""), consumed.body());
        assertTrue(consumed.body().contains("\"consumed\":true"), consumed.body());

        HttpResponse<String> ignored = post("/api/v1/client/screen/click",
                "{\"x\":9,\"y\":9}", "Authorization", "Bearer " + TOKEN);
        assertTrue(ignored.body().contains("\"consumed\":false"), ignored.body());

        HttpResponse<String> wrongMode = post("/api/v1/client/screen/click",
                "{\"x\":1,\"y\":2,\"mode\":\"admin\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(400, wrongMode.statusCode(), wrongMode.body());
        assertTrue(wrongMode.body().contains("no silent fallbacks"), wrongMode.body());

        HttpResponse<String> missing = post("/api/v1/client/screen/click", "{}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, missing.statusCode(), missing.body());
    }

    @Test
    void clientScreenshotContract() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> unregistered = post("/api/v1/client/screenshot", "{}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, unregistered.statusCode(), unregistered.body());

        registerFakeClientOps();
        HttpResponse<String> first = post("/api/v1/client/screenshot", "{}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"frameId\":1"), first.body());
        assertTrue(first.body().contains("\"bytes\":4242"), first.body());
        HttpResponse<String> second = post("/api/v1/client/screenshot", "{}",
                "Authorization", "Bearer " + TOKEN);
        assertTrue(second.body().contains("\"frameId\":2"), second.body());
    }

    // ------------------------------------------------------------------
    // Command execution (spec §6.2)
    // ------------------------------------------------------------------

    @Test
    void commandExecuteContract() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> noServer = post("/api/v1/server/commands/execute",
                "{\"command\":\"say hi\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(409, noServer.statusCode(), noServer.body());
        assertTrue(noServer.body().contains("WRONG_STATE"), noServer.body());

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        HttpResponse<String> ok = post("/api/v1/server/commands/execute",
                "{\"command\":\"say hi\",\"expectedWorldSessionId\":\""
                        + runtime.worldSessionId().orElseThrow() + "\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"result\":1"), ok.body());
        assertTrue(ok.body().contains("\"success\":true"), ok.body());
        assertTrue(ok.body().contains("say hi"), ok.body());
        assertTrue(ok.body().contains("\"permissionLevel\":2"), ok.body());

        HttpResponse<String> missing = post("/api/v1/server/commands/execute", "{}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, missing.statusCode(), missing.body());
        assertTrue(missing.body().contains("INVALID_PAYLOAD"), missing.body());
    }

    @Test
    void commandExecutionRequiresScope() throws Exception {
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        HttpResponse<String> forbidden = post("/api/v1/server/commands/execute",
                "{\"command\":\"say hi\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("\"required\":\"commands.execute\""), forbidden.body());
    }

    // ------------------------------------------------------------------
    // Logs / crash reports (slice 4.2)
    // ------------------------------------------------------------------

    @Test
    void logsEndpointsPaginateAndFilter() throws Exception {
        int freePort = freePort();
        gameDirOverride = Files.createTempDirectory("mapi-logs-test");
        Files.createDirectories(gameDirOverride.resolve("logs"));
        Files.writeString(gameDirOverride.resolve("logs").resolve("latest.log"), String.join("\n",
                "[12:00:01] [main/INFO]: Starting minecraft server",
                "[12:00:02] [main/ERROR]: Failed to load bonus pack",
                "[12:00:03] [Server thread/INFO]: Done (2.1s)! For help, type help",
                "[12:00:04] [main/WARN]: something odd",
                "[12:00:05] [main/ERROR]: disk nearly full"), StandardCharsets.UTF_8);
        Files.createDirectories(gameDirOverride.resolve("crash-reports"));
        Files.writeString(gameDirOverride.resolve("crash-reports")
                .resolve("crash-2026-09-13_12.00.00-server.txt"), "boom", StandardCharsets.UTF_8);

        platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return Path.of("config");
            }

            @Override
            public Path gameDir() {
                return gameDirOverride;
            }
        };
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort, TOKEN, 60), runtime, LOG);
        assertTrue(server.start());
        port = freePort;

        HttpResponse<String> all = get("/api/v1/logs?limit=2", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, all.statusCode(), all.body());
        assertTrue(all.body().contains("\"provenance\":\"game-logs-untrusted\""), all.body());
        assertTrue(all.body().contains("\"n\":0"), all.body());
        assertTrue(all.body().contains("\"nextCursor\":2"), all.body());
        assertTrue(all.body().contains("\"truncated\":true"), all.body());

        HttpResponse<String> rest = get("/api/v1/logs?cursor=2&limit=100",
                "Authorization", "Bearer " + TOKEN);
        assertTrue(rest.body().contains("\"nextCursor\":5"), rest.body());
        assertTrue(rest.body().contains("\"truncated\":false"), rest.body());

        HttpResponse<String> errors = get("/api/v1/logs/errors", "Authorization", "Bearer " + TOKEN);
        assertTrue(errors.body().contains("Failed to load bonus"), errors.body());
        assertTrue(errors.body().contains("disk nearly full"), errors.body());
        assertFalse(errors.body().contains("Done ("), errors.body());

        HttpResponse<String> crashes = get("/api/v1/crash-reports", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, crashes.statusCode(), crashes.body());
        assertTrue(crashes.body().contains("crash-2026-09-13_12.00.00-server.txt"), crashes.body());
        assertTrue(crashes.body().contains("\"total\":1"), crashes.body());
    }

    // ------------------------------------------------------------------
    // Capabilities (slice 4.1, spec §5)
    // ------------------------------------------------------------------

    @Test
    void capabilitiesReflectDynamicState() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> all = get("/api/v1/capabilities", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, all.statusCode(), all.body());
        // observe-authorized core op: fully available under the full scope set.
        assertTrue(all.body().contains("\"op\":\"tasks.create\",\"supported\":true,\"enabled\":true,"
                + "\"available\":true,\"authorized\":true,\"coverage\":\"full\",\"tier\":\"core\""), all.body());        // world ops are listed but unavailable without an active server session.
        int worldBlock = all.body().indexOf("\"op\":\"server.world.block\"");
        assertTrue(worldBlock >= 0, all.body());
        String entry = all.body().substring(worldBlock, all.body().indexOf('}', worldBlock) + 1);
        assertTrue(entry.contains("\"available\":false"), entry);

        HttpResponse<String> core = get("/api/v1/capabilities?tier=core",
                "Authorization", "Bearer " + TOKEN);
        assertTrue(core.body().contains("\"op\":\"leases.acquire\""), core.body());
        assertFalse(core.body().contains("\"op\":\"unsafe.reflect\""), core.body());
    }

    @Test
    void capabilitiesScopeAndSwitchState() throws Exception {
        // observe-only token: unsafe ops show authorized=false and enabled=false.
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        HttpResponse<String> restricted = get("/api/v1/capabilities?tier=experimental",
                "Authorization", "Bearer " + TOKEN);
        assertTrue(restricted.body().contains("\"op\":\"unsafe.reflect\""), restricted.body());
        assertTrue(restricted.body().contains("\"authorized\":false"), restricted.body());
        assertTrue(restricted.body().contains("\"enabled\":false"), restricted.body());
    }

    // ------------------------------------------------------------------
    // Storage read (slice 2.5)
    // ------------------------------------------------------------------

    @Test
    void storageEndpointContract() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());

        HttpResponse<String> ok = get("/api/v1/server/world/storage?dimension=minecraft:overworld&x=0&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"typeId\":\"minecraft:chest\""), ok.body());
        assertTrue(ok.body().contains("\"itemId\":\"minecraft:diamond\""), ok.body());
        assertTrue(ok.body().contains("\"count\":3"), ok.body());
        assertTrue(ok.body().contains("\"totalSlots\":27"), ok.body());
        assertTrue(ok.body().contains("\"units\":\"item-counts\""), ok.body());

        HttpResponse<String> notContainer = get(
                "/api/v1/server/world/storage?dimension=minecraft:overworld&x=998&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, notContainer.statusCode(), notContainer.body());
        assertTrue(notContainer.body().contains("\"reason\":\"NOT_A_CONTAINER\""), notContainer.body());
        assertTrue(notContainer.body().contains("\"typeId\":\"minecraft:furnace\""), notContainer.body());

        HttpResponse<String> noEntity = get(
                "/api/v1/server/world/storage?dimension=minecraft:overworld&x=997&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, noEntity.statusCode(), noEntity.body());
        assertTrue(noEntity.body().contains("\"reason\":\"NO_BLOCK_ENTITY\""), noEntity.body());

        HttpResponse<String> unloaded = get(
                "/api/v1/server/world/storage?dimension=minecraft:overworld&x=999&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, unloaded.statusCode(), unloaded.body());
        assertTrue(unloaded.body().contains("CHUNK_UNLOADED"), unloaded.body());
    }

    // ------------------------------------------------------------------
    // Block entity read (slice 2.3)
    // ------------------------------------------------------------------

    @Test
    void blockEntityEndpointContract() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());

        HttpResponse<String> ok = get("/api/v1/server/world/block-entity?dimension=minecraft:overworld&x=0&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ok.statusCode(), ok.body());
        assertTrue(ok.body().contains("\"typeId\":\"minecraft:chest\""), ok.body());
        assertTrue(ok.body().contains("\"available\":true"), ok.body());
        assertTrue(ok.body().contains("\"nbt\":{\"Items\":{\"list\":["), ok.body());
        assertTrue(ok.body().contains("\"Lock\":\"secret-code\""), ok.body());

        HttpResponse<String> absent = get(
                "/api/v1/server/world/block-entity?dimension=minecraft:overworld&x=998&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, absent.statusCode(), absent.body());
        assertTrue(absent.body().contains("\"reason\":\"NO_BLOCK_ENTITY\""), absent.body());

        HttpResponse<String> unloaded = get(
                "/api/v1/server/world/block-entity?dimension=minecraft:overworld&x=999&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, unloaded.statusCode(), unloaded.body());
        assertTrue(unloaded.body().contains("CHUNK_UNLOADED"), unloaded.body());

        HttpResponse<String> unknownDim = get(
                "/api/v1/server/world/block-entity?dimension=minecraft:nowhere&x=0&y=0&z=0",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknownDim.statusCode(), unknownDim.body());
        assertTrue(unknownDim.body().contains("DIMENSION_NOT_FOUND"), unknownDim.body());
    }

    // ------------------------------------------------------------------
    // Sandboxed files (slice 3.2)
    // ------------------------------------------------------------------

    @Test
    void fileSurfaceGatingAndContract() throws Exception {
        // Disabled by default.
        startServer(new MapiConfig(true, freePort(), TOKEN, 60));
        HttpResponse<String> disabled = get("/api/v1/files?path=", "Authorization", "Bearer " + TOKEN);
        assertEquals(403, disabled.statusCode(), disabled.body());
        assertTrue(disabled.body().contains("DISABLED"), disabled.body());

        // Scope enforcement: observe-only token cannot read files.
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        HttpResponse<String> forbidden = get("/api/v1/files?path=", "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("\"required\":\"files.read\""), forbidden.body());
    }

    @Test
    void fileReadWriteRoundTripWithDenylist() throws Exception {
        int freePort = freePort();
        platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return Path.of("config");
            }

            @Override
            public Path gameDir() {
                return gameDirOverride;
            }
        };
        gameDirOverride = Files.createTempDirectory("mapi-files-test");
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort, TOKEN, 60, "mapi-" + freePort, null, 0,
                false, MapiConfig.DEFAULT_DISCOVERY_HEARTBEAT_SECONDS,
                java.util.Set.of(dev.example.mapi.internal.auth.Scope.OBSERVE,
                        dev.example.mapi.internal.auth.Scope.FILES_READ,
                        dev.example.mapi.internal.auth.Scope.FILES_WRITE),
                MapiConfig.DEFAULT_COMMAND_PERMISSION_LEVEL, false, true), runtime, LOG);
        assertTrue(server.start());
        port = freePort;

        // Write inside the sandbox.
        HttpResponse<String> written = post("/api/v1/files",
                "{\"path\":\"notes/todo.txt\",\"contentBase64\":\""
                        + java.util.Base64.getEncoder().encodeToString("do stuff".getBytes()) + "\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, written.statusCode(), written.body());
        assertTrue(Files.readString(gameDirOverride.resolve("notes").resolve("todo.txt"))
                .equals("do stuff"));

        // Read it back (dir listing + file content).
        HttpResponse<String> dir = get("/api/v1/files?path=notes", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, dir.statusCode(), dir.body());
        assertTrue(dir.body().contains("\"type\":\"dir\""), dir.body());
        assertTrue(dir.body().contains("\"name\":\"todo.txt\""), dir.body());

        HttpResponse<String> file = get("/api/v1/files?path=notes/todo.txt",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, file.statusCode(), file.body());
        assertTrue(file.body().contains("\"encoding\":\"utf-8\""), file.body());
        assertTrue(file.body().contains("\"content\":\"do stuff\""), file.body());

        // Denylist: token file protected even though it exists in the game dir.
        Files.createDirectories(gameDirOverride.resolve("mcapi"));
        Files.writeString(gameDirOverride.resolve("mcapi").resolve("token"), TOKEN + "\n");
        HttpResponse<String> denied = get("/api/v1/files?path=mcapi/token",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(403, denied.statusCode(), denied.body());
        assertTrue(denied.body().contains("DENIED_PATH"), denied.body());

        HttpResponse<String> escape = get("/api/v1/files?path=../../etc/passwd",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(403, escape.statusCode(), escape.body());
        assertTrue(escape.body().contains("DENIED_PATH"), escape.body());

        // Missing path entry.
        HttpResponse<String> missing = get("/api/v1/files?path=not/here.txt",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, missing.statusCode(), missing.body());
    }

    private Path gameDirOverride;

    // ------------------------------------------------------------------
    // Unsafe reflection surface (slice 3.1)
    // ------------------------------------------------------------------

    @Test
    void unsafeReflectionGatingAndBehavior() throws Exception {
        startServer(scopedConfig(java.util.List.of(
                dev.example.mapi.internal.auth.Scope.OBSERVE,
                dev.example.mapi.internal.auth.Scope.UNSAFE_EXECUTE)));
        // Disabled by default (reflection.enabled=false, spec §4.5).
        HttpResponse<String> disabled = post("/api/v1/unsafe/reflect",
                "{\"class\":\"java.lang.String\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(403, disabled.statusCode(), disabled.body());
        assertTrue(disabled.body().contains("DISABLED"), disabled.body());
        // The scope check fires before the disabled check.
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        HttpResponse<String> forbidden = post("/api/v1/unsafe/invoke",
                "{\"class\":\"java.lang.System\",\"method\":\"lineSeparator\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("\"required\":\"unsafe.execute\""), forbidden.body());
    }

    @Test
    void unsafeReflectionDescribeAndInvoke() throws Exception {
        int freePort = freePort();
        startServer(new MapiConfig(true, freePort, TOKEN, 60, "mapi-" + freePort, null, 0, false,
                MapiConfig.DEFAULT_DISCOVERY_HEARTBEAT_SECONDS,
                java.util.Set.of(dev.example.mapi.internal.auth.Scope.OBSERVE,
                        dev.example.mapi.internal.auth.Scope.UNSAFE_EXECUTE),
                MapiConfig.DEFAULT_COMMAND_PERMISSION_LEVEL, true, false));

        HttpResponse<String> describe = post("/api/v1/unsafe/reflect",
                "{\"class\":\"java.lang.String\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, describe.statusCode(), describe.body());
        assertTrue(describe.body().contains("\"className\":\"java.lang.String\""), describe.body());
        assertTrue(describe.body().contains("\"methods\":["), describe.body());

        HttpResponse<String> invoke = post("/api/v1/unsafe/invoke",
                "{\"class\":\"java.lang.System\",\"method\":\"lineSeparator\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, invoke.statusCode(), invoke.body());
        assertTrue(invoke.body().contains("\"resultType\":\"java.lang.String\""), invoke.body());

        HttpResponse<String> unknownClass = post("/api/v1/unsafe/reflect",
                "{\"class\":\"no.such.Class\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknownClass.statusCode(), unknownClass.body());

        HttpResponse<String> badInvoke = post("/api/v1/unsafe/invoke",
                "{\"class\":\"java.lang.System\",\"method\":\"noSuchMethod\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, badInvoke.statusCode(), badInvoke.body());
        assertTrue(badInvoke.body().contains("INVALID_PAYLOAD"), badInvoke.body());
    }

    // ------------------------------------------------------------------
    // Diagnostics (slice 2.2, diagnostics scope)
    // ------------------------------------------------------------------

    @Test
    void diagnosticsEndpointsAndScope() throws Exception {
        startServer(scopedConfig(java.util.List.of(
                dev.example.mapi.internal.auth.Scope.OBSERVE,
                dev.example.mapi.internal.auth.Scope.DIAGNOSTICS)));

        HttpResponse<String> threads = get("/api/v1/threads?limit=10", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, threads.statusCode(), threads.body());
        assertTrue(threads.body().contains("\"name\":\""), threads.body());
        assertTrue(threads.body().contains("\"total\":"), threads.body());

        HttpResponse<String> gc = post("/api/v1/memory/gc", "{}", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, gc.statusCode(), gc.body());
        assertTrue(gc.body().contains("\"heapUsedBeforeBytes\":"), gc.body());
        assertTrue(gc.body().contains("\"reclaimedBytes\":"), gc.body());
    }

    @Test
    void diagnosticsRequiresScope() throws Exception {
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        HttpResponse<String> forbidden = get("/api/v1/threads", "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("\"required\":\"diagnostics\""), forbidden.body());
    }

    // ------------------------------------------------------------------
    // Extension SPI (slice 2.4)
    // ------------------------------------------------------------------

    @Test
    void extensionSpiDispatchScopeAndSchema() throws Exception {
        startServer(enabledConfig());
        runtime.services().register("example-ext",
                new dev.example.mapi.api.MapiHttpExtension() {
                    @Override
                    public String id() {
                        return "example-ext";
                    }

                    @Override
                    public String requiredScope() {
                        return dev.example.mapi.internal.auth.Scope.OBSERVE;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of("operations", Map.of("ping", "returns pong"));
                    }

                    @Override
                    public dev.example.mapi.api.MapiHttpExtension.MapiHttpResponse handle(
                            dev.example.mapi.api.MapiHttpExtension.MapiHttpRequest request) {
                        return new dev.example.mapi.api.MapiHttpExtension.MapiHttpResponse(200,
                                Map.of("pong", request.method(), "name", request.query().getOrDefault("name", ""),
                                        "echo", request.body().getOrDefault("msg", "")));
                    }
                });

        HttpResponse<String> ping = get("/api/v1/ext/example-ext/ping?name=adam",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, ping.statusCode(), ping.body());
        assertTrue(ping.body().contains("\"pong\":\"GET\""), ping.body());
        assertTrue(ping.body().contains("\"name\":\"adam\""), ping.body());

        HttpResponse<String> posted = post("/api/v1/ext/example-ext/ping", "{\"msg\":\"hello\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, posted.statusCode(), posted.body());
        assertTrue(posted.body().contains("\"echo\":\"hello\""), posted.body());

        HttpResponse<String> schema = get("/api/v1/ext/example-ext/$schema",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, schema.statusCode(), schema.body());
        assertTrue(schema.body().contains("\"operations\""), schema.body());

        HttpResponse<String> unknown = get("/api/v1/ext/nope/ping",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertTrue(unknown.body().contains("Unknown extension"), unknown.body());
    }

    @Test
    void extensionScopeEnforced() throws Exception {
        startServer(scopedConfig(java.util.List.of(dev.example.mapi.internal.auth.Scope.OBSERVE)));
        runtime.services().register("writer-ext",
                new dev.example.mapi.api.MapiHttpExtension() {
                    @Override
                    public String id() {
                        return "writer-ext";
                    }

                    @Override
                    public String requiredScope() {
                        return dev.example.mapi.internal.auth.Scope.WORLD_WRITE;
                    }

                    @Override
                    public Map<String, Object> schema() {
                        return Map.of();
                    }

                    @Override
                    public dev.example.mapi.api.MapiHttpExtension.MapiHttpResponse handle(
                            dev.example.mapi.api.MapiHttpExtension.MapiHttpRequest request) {
                        return new dev.example.mapi.api.MapiHttpExtension.MapiHttpResponse(200, Map.of());
                    }
                });
        HttpResponse<String> forbidden = get("/api/v1/ext/writer-ext/ping",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(403, forbidden.statusCode(), forbidden.body());
        assertTrue(forbidden.body().contains("FORBIDDEN_SCOPE"), forbidden.body());
    }

    // ------------------------------------------------------------------
    // Registry / tags / mods inspection (slice 2.1)
    // ------------------------------------------------------------------

    @Test
    void registryTagsAndModsInspection() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());

        HttpResponse<String> registry = get("/api/v1/registry/block?limit=2&offset=1",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, registry.statusCode(), registry.body());
        assertTrue(registry.body().contains("\"ids\":[\"minecraft:dirt\",\"minecraft:stone\"]"), registry.body());
        assertTrue(registry.body().contains("\"total\":3"), registry.body());
        assertTrue(registry.body().contains("\"truncated\":false"), registry.body());

        HttpResponse<String> truncated = get("/api/v1/registry/block?limit=2&offset=0",
                "Authorization", "Bearer " + TOKEN);
        assertTrue(truncated.body().contains("\"truncated\":true"), truncated.body());

        HttpResponse<String> single = get("/api/v1/registry/block?id=minecraft:stone",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, single.statusCode(), single.body());
        assertTrue(single.body().contains("\"present\":true"), single.body());

        HttpResponse<String> unknownType = get("/api/v1/registry/bogus",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(404, unknownType.statusCode(), unknownType.body());
        assertTrue(unknownType.body().contains("REGISTRY_TYPE_NOT_FOUND"), unknownType.body());

        HttpResponse<String> tags = get("/api/v1/tags/block", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, tags.statusCode(), tags.body());
        assertTrue(tags.body().contains("\"tags\":[\"minecraft:logs\",\"minecraft:planks\"]"), tags.body());

        HttpResponse<String> members = get("/api/v1/tags/block?tag=minecraft:planks",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, members.statusCode(), members.body());
        assertTrue(members.body().contains("\"members\":[\"minecraft:oak_planks\",\"minecraft:spruce_planks\"]"),
                members.body());

        HttpResponse<String> mods = get("/api/v1/mods", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, mods.statusCode(), mods.body());
        assertTrue(mods.body().contains("\"id\":\"mapi\""), mods.body());
        assertTrue(mods.body().contains("\"total\":1"), mods.body());
    }

    // ------------------------------------------------------------------
    // Leases (spec §5.3)
    // ------------------------------------------------------------------

    @Test
    void leaseLifecycleOverHttp() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> first = post("/api/v1/leases", "{\"lease\":\"client.input\",\"ttlMs\":60000}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, first.statusCode(), first.body());
        assertTrue(first.body().contains("\"state\":\"held\""), first.body());
        String firstId = first.body().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");

        HttpResponse<String> rejected = post("/api/v1/leases", "{\"lease\":\"client.input\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, rejected.statusCode(), rejected.body());
        assertTrue(rejected.body().contains("LEASE_HELD"), rejected.body());
        assertTrue(rejected.body().contains("\"held\":"), rejected.body());

        HttpResponse<String> preempt = post("/api/v1/leases",
                "{\"lease\":\"client.input\",\"conflict\":\"preempt\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, preempt.statusCode(), preempt.body());
        assertTrue(preempt.body().contains("\"state\":\"held\""), preempt.body());
        String secondId = preempt.body().replaceAll(".*\"id\":\"([0-9a-f-]+)\".*", "$1");
        HttpResponse<String> firstAfter = get("/api/v1/leases", "Authorization", "Bearer " + TOKEN);
        assertTrue(firstAfter.body().contains("\"state\":\"preempted\""), firstAfter.body());

        HttpResponse<String> renew = post("/api/v1/leases/" + secondId + "/renew", "{\"ttlMs\":120000}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, renew.statusCode(), renew.body());

        HttpResponse<String> release = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/leases/" + secondId))
                .header("Authorization", "Bearer " + TOKEN)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, release.statusCode(), release.body());
        assertTrue(release.body().contains("\"state\":\"released\""), release.body());
        assertTrue(firstId != null && !firstId.isEmpty());
    }

    @Test
    void leaseValidationAndScopeErrors() throws Exception {
        startServer(enabledConfig());
        HttpResponse<String> unknownType = post("/api/v1/leases", "{\"lease\":\"bogus.lease\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(400, unknownType.statusCode(), unknownType.body());
        assertTrue(unknownType.body().contains("INVALID_PAYLOAD"), unknownType.body());
    }

    @Test
    void clientInputLeaseExpiryReleasesHeldKeys() throws Exception {
        startServer(enabledConfig());
        registerFakeClientOps();
        HttpResponse<String> pressed = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"press\"}", "Authorization", "Bearer " + TOKEN);
        assertEquals(200, pressed.statusCode(), pressed.body());
        int before = releasedKeys.get();

        HttpResponse<String> lease = post("/api/v1/leases", "{\"lease\":\"client.input\",\"ttlMs\":1000}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(200, lease.statusCode(), lease.body());

        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline && releasedKeys.get() == before) {
            Thread.sleep(100);
        }
        assertTrue(releasedKeys.get() > before,
                "client.input lease expiry must run the key-release hook");
    }

    // ------------------------------------------------------------------
    // Session preconditions (spec §1.1/§2.3)
    // ------------------------------------------------------------------

    @Test
    void staleSessionPreconditionsOnMutations() throws Exception {
        startServer(enabledConfig());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        String worldSessionId = runtime.worldSessionId().orElseThrow();

        HttpResponse<String> matching = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":42},"
                        + "\"expectedWorldSessionId\":\"" + worldSessionId + "\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(202, matching.statusCode(), matching.body());

        HttpResponse<String> stale = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":42},"
                        + "\"expectedWorldSessionId\":\"00000000-0000-0000-0000-0000000000aa\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(stale.body().contains("STALE_SESSION"), stale.body());
        assertTrue(stale.body().contains("\"currentWorldSessionId\":\"" + worldSessionId + "\""), stale.body());

        platform.lifecycleListener().onServerStopping();
        platform.lifecycleListener().onServerStopped();
        HttpResponse<String> afterSession = post("/api/v1/tasks",
                "{\"kind\":\"wait-for-tick\",\"payload\":{\"targetTick\":42},"
                        + "\"expectedWorldSessionId\":\"" + worldSessionId + "\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, afterSession.statusCode(), afterSession.body());
        assertTrue(afterSession.body().contains("\"currentWorldSessionId\":null"), afterSession.body());

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        registerFakeClientOps();
        HttpResponse<String> connectionGuard = post("/api/v1/client/input/key",
                "{\"mapping\":\"key.forward\",\"action\":\"press\","
                        + "\"expectedConnectionSessionId\":\"abc\"}",
                "Authorization", "Bearer " + TOKEN);
        assertEquals(409, connectionGuard.statusCode(), connectionGuard.body());
        assertTrue(connectionGuard.body().contains("CONNECTION_SESSION_UNAVAILABLE"), connectionGuard.body());
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    void httpRunsForTheWholeProcessOnceEnabled(@TempDir Path instanceDir) throws Exception {
        int port = freePort();
        Files.createDirectories(instanceDir.resolve("config"));
        Files.writeString(instanceDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                "[http]\nenabled = true\nport = " + port + "\ntoken = \"token-from-config-0123456789\"\n");

        TestPlatform platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return instanceDir.resolve("config");
            }

            @Override
            public Path gameDir() {
                return instanceDir;
            }
        };
        MapiRuntime lifecycleRuntime = new MapiRuntime(platform);
        Path discovery = instanceDir.resolve("mcapi").resolve("discovery.json");
        try {
            assertTrue(lifecycleRuntime.httpRunning(), "HTTP must start with the process when enabled");
            assertTrue(Files.isRegularFile(discovery), "discovery file must exist while the API runs");
            String discoveryJson = Files.readString(discovery);
            assertTrue(discoveryJson.startsWith("{\"schemaVersion\":1,"), discoveryJson);
            assertFalse(discoveryJson.contains("token"), "discovery must never contain token fields");
            assertTrue(discoveryJson.contains("\"readiness\":\"http\""), "pre-world readiness");

            platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
            assertTrue(Files.readString(discovery).contains("\"readiness\":\"worldReady\""),
                    "readiness must follow the world session");
            HttpResponse<String> health = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/ready"))
                    .header("Authorization", "Bearer token-from-config-0123456789")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(health.body().contains("\"worldReady\":true"), health.body());

            platform.lifecycleListener().onServerStopping();
            platform.lifecycleListener().onServerStopped();
            assertTrue(lifecycleRuntime.httpRunning(), "HTTP must survive the end of a world session");
            assertTrue(Files.readString(discovery).contains("\"readiness\":\"http\""),
                    "readiness must return to http after the world session");
        } finally {
            lifecycleRuntime.shutdown();
        }
        assertFalse(Files.exists(discovery), "discovery file must be removed on shutdown");
        try (var probe = new java.net.ServerSocket(port)) {
            // Re-binding must succeed: the listener released the port.
        }
    }

    @Test
    void portFallbackBindsNextPortWhenPrimaryIsBusy(@TempDir Path instanceDir) throws Exception {
        try (var blocker = new java.net.ServerSocket(0)) {
            int primary = blocker.getLocalPort();
            Files.createDirectories(instanceDir.resolve("config"));
            Files.writeString(instanceDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                    "[http]\nenabled = true\nport = " + primary + "\nportFallback = 2\n"
                            + "token = \"token-from-config-0123456789\"\n");

            TestPlatform platform = new TestPlatform(LOG) {
                @Override
                public Path configDir() {
                    return instanceDir.resolve("config");
                }

                @Override
                public Path gameDir() {
                    return instanceDir;
                }
            };
            MapiRuntime lifecycleRuntime = new MapiRuntime(platform);
            try {
                assertTrue(lifecycleRuntime.httpRunning(), "fallback port must be bound");
                int boundPort = lifecycleRuntime.httpBoundPort();
                assertTrue(boundPort > primary && boundPort <= primary + 2,
                        "bound port must be a fallback: " + boundPort);
                HttpResponse<String> health = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + boundPort + "/api/v1/health"))
                        .header("Authorization", "Bearer token-from-config-0123456789")
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, health.statusCode());
                String discovery = Files.readString(instanceDir.resolve("mcapi").resolve("discovery.json"));
                assertTrue(discovery.contains("\"port\":" + boundPort), discovery);
            } finally {
                lifecycleRuntime.shutdown();
            }
        }
    }

    @Test
    void failFastRefusesToStartWithoutABindablePort(@TempDir Path instanceDir) throws Exception {
        int primary;
        try (var blocker = new java.net.ServerSocket(0)) {
            primary = blocker.getLocalPort();
        }
        try (var blocker = new java.net.ServerSocket(primary)) {
            Files.createDirectories(instanceDir.resolve("config"));
            Files.writeString(instanceDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                    "[http]\nenabled = true\nport = " + primary + "\nfailFast = true\n"
                            + "token = \"token-from-config-0123456789\"\n");
            TestPlatform platform = new TestPlatform(LOG) {
                @Override
                public Path configDir() {
                    return instanceDir.resolve("config");
                }

                @Override
                public Path gameDir() {
                    return instanceDir;
                }
            };
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new MapiRuntime(platform));
            assertTrue(e.getMessage().contains("failFast"), e.getMessage());
        }
    }

    @Test
    void bindConflictIsReportedNotThrown() throws Exception {
        int port;
        try (var blocker = new java.net.ServerSocket(0)) {
            port = blocker.getLocalPort();
        }
        try (var blocker = new java.net.ServerSocket(port)) {
            runtime = new MapiRuntime(new TestPlatform(LOG));
            server = new HttpApiServer(new MapiConfig(true, port, TOKEN, 60), runtime, LOG);
            assertFalse(server.start(), "bind must fail without throwing");
            assertFalse(runtime.httpRunning());
        }
    }

    @Test
    void stopFreesThePort() throws Exception {
        startServer(enabledConfig());
        assertEquals(200, get("/api/v1/health", "Authorization", "Bearer " + TOKEN).statusCode());
        server.stop();
        try (var probe = new java.net.ServerSocket(port)) {
            // Re-binding must succeed: the old listener released the port.
        }
    }

    @Test
    void bindsDeterministicIpv4Loopback() throws Exception {
        startServer(enabledConfig());
        assertEquals("127.0.0.1", server.boundAddress().getAddress().getHostAddress());
    }
}
