package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        HttpRequest post = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> postResponse = client.send(post, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, postResponse.statusCode());
        assertEquals(Optional.of("GET"), postResponse.headers().firstValue("Allow"));

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
    // Lifecycle
    // ------------------------------------------------------------------

    @Test
    void lifecycleStartsAndStopsWithServerWhenEnabledByConfig(@TempDir Path instanceDir) throws Exception {
        int port = freePort();
        Files.createDirectories(instanceDir.resolve("config"));
        Files.writeString(instanceDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                "http.enabled=true\nhttp.port=" + port + "\nhttp.token=token-from-config-0123456789\n");

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
        assertFalse(lifecycleRuntime.httpRunning(), "HTTP must be off until a server starts");
        assertFalse(Files.exists(instanceDir.resolve("mcapi").resolve("discovery.json")),
                "no discovery file while the API is disabled");

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        assertTrue(lifecycleRuntime.httpRunning());

        Path discovery = instanceDir.resolve("mcapi").resolve("discovery.json");
        assertTrue(Files.isRegularFile(discovery), "discovery file must exist while the API runs");
        String discoveryJson = Files.readString(discovery);
        assertTrue(discoveryJson.startsWith("{\"schemaVersion\":1,"), discoveryJson);
        assertFalse(discoveryJson.contains("token-from-config-0123456789"),
                "discovery must never contain the token");
        assertFalse(discoveryJson.contains("token"), "discovery must never contain token fields");

        HttpResponse<String> health = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .header("Authorization", "Bearer token-from-config-0123456789")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());

        HttpResponse<String> info = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/info"))
                .header("Authorization", "Bearer token-from-config-0123456789")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        String infoJson = info.body();
        String prefix = "\"processSessionId\":\"";
        int start = infoJson.indexOf(prefix) + prefix.length();
        String processSessionId = infoJson.substring(start, infoJson.indexOf('"', start));
        assertTrue(discoveryJson.contains("\"processSessionId\":\"" + processSessionId + "\""),
                "discovery must carry the same process session id as /info");

        platform.lifecycleListener().onServerStopping();
        platform.lifecycleListener().onServerStopped();
        assertFalse(lifecycleRuntime.httpRunning(), "HTTP must stop with the server");
        assertFalse(Files.exists(discovery), "discovery file must be removed when the API stops");
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
