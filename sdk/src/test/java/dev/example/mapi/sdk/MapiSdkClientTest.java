package dev.example.mapi.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * SDK behavioral suite (spec §7.5): the Java SDK against a fake MAPI server.
 * Verifies the same behaviors the TS and Python SDKs implement: health,
 * world phase, snapshot POST, problem-code error envelopes.
 */
class MapiSdkClientTest {

    private static final String TOKEN = "sdk-token-0123456789";

    private final List<HttpServer> fakes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (var srv : fakes) {
            srv.stop(0);
        }
        fakes.clear();
    }

    private MapiSdkClient startFake() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/api/v1", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            String path = exchange.getRequestURI().getPath();
            int status;
            String body;
            if (auth == null || !auth.equals("Bearer " + TOKEN)) {
                status = 401;
                body = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"no\"}}";
            } else if (path.equals("/api/v1/health")) {
                status = 200;
                body = "{\"protocolVersion\":1,\"status\":\"ok\"}";
            } else if (path.equals("/api/v1/server/world")) {
                status = 200;
                body = "{\"phase\":\"ACTIVE\",\"bridgeId\":\"test\"}";
            } else if (path.equals("/api/v1/server/snapshots")
                    && "POST".equals(exchange.getRequestMethod())) {
                status = 200;
                body = "{\"snapshotId\":\"snap-1\",\"boundary\":42}";
            } else {
                status = 404;
                body = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"unknown\"}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        fakes.add(server);
        return new MapiSdkClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), TOKEN);
    }

    private MapiSdkClient startFake401() throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"no\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        fakes.add(server);
        return new MapiSdkClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "wrong-token");
    }

    @Test
    void healthReturns200WithOkStatus() throws IOException {
        var result = startFake().get("/api/v1/health");
        assertEquals(200, result.status());
        assertTrue(result.ok());
        assertTrue(result.errorCode().isEmpty());
    }

    @Test
    void unauthorizedThrowsWithProblemCode() throws IOException {
        MapiSdkClient bad = startFake401();
        try {
            bad.get("/api/v1/health");
            org.junit.jupiter.api.Assertions.fail("expected MapiSdkException");
        } catch (MapiSdkClient.MapiSdkException e) {
            assertEquals(401, e.getStatus());
            assertEquals("UNAUTHORIZED", e.getCode());
        }
    }

    @Test
    void worldPhaseIsReadable() throws IOException {
        var client = startFake();
        var world = client.get("/api/v1/server/world");
        assertEquals("ACTIVE", world.body().get("phase"));
    }

    @Test
    void snapshotPostReturnsBody() throws IOException {
        var client = startFake();
        var result = client.post("/api/v1/server/snapshots",
                java.util.Map.of("label", "test"));
        assertEquals(200, result.status());
        assertEquals("snap-1", result.body().get("snapshotId"));
    }

    @Test
    void notFoundGivesProblemCode() throws IOException {
        var client = startFake();
        try {
            client.get("/api/v1/nonexistent");
            org.junit.jupiter.api.Assertions.fail("expected MapiSdkException");
        } catch (MapiSdkClient.MapiSdkException e) {
            assertEquals(404, e.getStatus());
            assertEquals("NOT_FOUND", e.getCode());
        }
    }

    @Test
    void blankTokenIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MapiSdkClient("http://127.0.0.1:25586", ""));
    }
}
