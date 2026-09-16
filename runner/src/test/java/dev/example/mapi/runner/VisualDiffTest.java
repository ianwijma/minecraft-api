package dev.example.mapi.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Visual baseline comparison (spec §16/§18) with synthetic PNGs. */
class VisualDiffTest {

    private static final String TOKEN = "visual-token-0123456789";

    /** Fake screenshot API: returns the queued PNG, then any other capture. */
    private static HttpServer startFake(byte[][] queuedPngs) throws IOException {
        var index = new int[] {0};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/api/v1/client/screenshots", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            int status;
            byte[] body;
            if (!auth.equals("Bearer " + TOKEN)) {
                status = 401;
                body = "{\"error\":{\"code\":\"UNAUTHORIZED\"}}".getBytes(StandardCharsets.UTF_8);
            } else {
                int i = Math.min(index[0], queuedPngs.length - 1);
                index[0]++;
                status = 200;
                body = VisualDiff.fakeScreenshotBody(queuedPngs[i]).getBytes(StandardCharsets.UTF_8);
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

    private static VisualDiff client(int port) {
        return new VisualDiff(new MapiClient("http://127.0.0.1:" + port, TOKEN));
    }

    @Test
    void firstCaptureCreatesBaselineThenMatchingCapturePasses() throws Exception {
        HttpServer fake = startFake(new byte[][] {
                VisualDiff.renderPng(64, 32, Color.BLUE),
                VisualDiff.renderPng(64, 32, Color.BLUE)});
        try {
            Path dir = Files.createTempDirectory("mapi-visual");
            VisualDiff.Outcome first = client(fake.getAddress().getPort())
                    .compareOrBaseline(dir, "menu", 0.01);
            assertTrue(first.ok(), first.detail());
            assertTrue(first.detail().contains("baseline created"));
            assertTrue(Files.isRegularFile(dir.resolve("menu.baseline.png")));

            VisualDiff.Outcome second = client(fake.getAddress().getPort())
                    .compareOrBaseline(dir, "menu", 0.01);
            assertTrue(second.ok(), second.detail());
            assertEquals(0, second.rmsDelta());
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void divergenceBeyondToleranceFails() throws Exception {
        HttpServer fake = startFake(new byte[][] {
                VisualDiff.renderPng(64, 32, Color.BLUE),
                VisualDiff.renderPng(64, 32, Color.RED)});
        try {
            Path dir = Files.createTempDirectory("mapi-visual");
            VisualDiff diff = client(fake.getAddress().getPort());
            assertTrue(diff.compareOrBaseline(dir, "menu", 0.01).ok());
            VisualDiff.Outcome outcome = diff.compareOrBaseline(dir, "menu", 0.01);
            assertTrue(!outcome.ok(), outcome.detail());
            assertTrue(outcome.detail().contains("visual divergence"));
            // Fully blue vs fully red: sqrt(2 * 255^2 / 3 channels) / 255.
            assertEquals(Math.sqrt(2.0 / 3.0), outcome.rmsDelta(), 0.0001);
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void withinTolerancePasses() throws Exception {
        HttpServer fake = startFake(new byte[][] {
                VisualDiff.renderPng(64, 32, Color.BLUE),
                VisualDiff.renderPng(64, 32, new Color(0, 0, 210))});
        try {
            Path dir = Files.createTempDirectory("mapi-visual");
            VisualDiff diff = client(fake.getAddress().getPort());
            diff.compareOrBaseline(dir, "menu", 0.5);
            VisualDiff.Outcome outcome = diff.compareOrBaseline(dir, "menu", 0.5);
            assertTrue(outcome.ok(), outcome.detail());
            assertTrue(outcome.rmsDelta() > 0 && outcome.rmsDelta() < 0.5);
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void sizeMismatchIsADivergenceNeverASilentPass() throws Exception {
        HttpServer fake = startFake(new byte[][] {
                VisualDiff.renderPng(64, 32, Color.BLUE),
                VisualDiff.renderPng(32, 16, Color.BLUE)});
        try {
            Path dir = Files.createTempDirectory("mapi-visual");
            VisualDiff diff = client(fake.getAddress().getPort());
            diff.compareOrBaseline(dir, "menu", 0.5);
            VisualDiff.Outcome outcome = diff.compareOrBaseline(dir, "menu", 0.5);
            assertTrue(!outcome.ok());
            assertTrue(outcome.detail().contains("size mismatch"));
        } finally {
            fake.stop(0);
        }
    }

    @Test
    void invalidToleranceIsRefused() throws Exception {
        Path dir = Files.createTempDirectory("mapi-visual");
        VisualDiff diff = client(1); // port 1: never reached (arg validation first)
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> diff.compareOrBaseline(dir, "menu", 1.5));
    }
}
