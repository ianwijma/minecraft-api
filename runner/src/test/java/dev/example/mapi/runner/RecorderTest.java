package dev.example.mapi.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Recording/replay/divergence tests (spec §16) against a fake MAPI server. */
class RecorderTest {

    private static final String TOKEN = "recorder-token-0123456789";

    private static MapiClient client(int port) {
        return new MapiClient("http://127.0.0.1:" + port, TOKEN);
    }

    /** Fake API whose snapshot endpoint can be toggled to fail. */
    private static HttpServer startFake(boolean snapshotsWork) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/api/v1", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            int status;
            if (auth == null || !auth.equals("Bearer " + TOKEN)) {
                status = 401;
                body = "{\"error\":{\"code\":\"UNAUTHORIZED\"}}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/health")) {
                status = 200;
                body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/server/snapshots")
                    && "POST".equals(exchange.getRequestMethod())) {
                if (snapshotsWork) {
                    status = 200;
                    body = "{\"snapshotId\":\"s1\"}".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 409;
                    body = "{\"error\":{\"code\":\"WORLD_NOT_LOADED\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                }
            } else {
                status = 404;
                body = "{\"error\":{\"code\":\"NOT_FOUND\"}}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    @Test
    void recordReplayRoundTripsWithNoDivergence() throws Exception {
        HttpServer fake = startFake(true);
        try {
            int port = fake.getAddress().getPort();
            Recorder recorder = new Recorder();
            recorder.record(0, "health", "GET", "/api/v1/health", null,
                    client(port).get("/api/v1/health").status(), null);
            recorder.record(1, "capture", "POST", "/api/v1/server/snapshots",
                    "{\"label\":\"rec\"}", client(port).post("/api/v1/server/snapshots",
                            "{\"label\":\"rec\"}").status(), null);

            Path file = Files.createTempFile("mapi-recording", ".jsonl");
            recorder.write(file);
            Recorder.ReplayReport report = Recorder.replay(client(port), file);
            assertTrue(report.ok());
            assertEquals(2, report.steps().size());
            assertEquals(0, report.divergences().size());
            Files.deleteIfExists(file);
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void replayDetectsStatusAndErrorDivergences() throws Exception {
        HttpServer healthy = startFake(true);
        int recordedPort = healthy.getAddress().getPort();
        Recorder recorder = new Recorder();
        recorder.record(0, "health", "GET", "/api/v1/health", null,
                client(recordedPort).get("/api/v1/health").status(), null);
        recorder.record(1, "capture", "POST", "/api/v1/server/snapshots",
                "{\"label\":\"rec\"}",
                client(recordedPort).post("/api/v1/server/snapshots", "{\"label\":\"rec\"}").status(),
                null);
        Path file = Files.createTempFile("mapi-recording", ".jsonl");
        recorder.write(file);
        healthy.stop(0);

        HttpServer broken = startFake(false); // snapshots now 409 WORLD_NOT_LOADED
        try {
            Recorder.ReplayReport report = Recorder.replay(client(broken.getAddress().getPort()), file);
            assertTrue(!report.ok());
            assertEquals(1, report.divergences().size());
            assertEquals("status", report.divergences().get(0).kind());
            assertEquals("200", report.divergences().get(0).expected());
            assertEquals("409", report.divergences().get(0).actual());
            Files.deleteIfExists(file);
        } finally {
            broken.stop(0);
        }
    }
}
