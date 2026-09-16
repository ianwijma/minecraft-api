package dev.example.mapi.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Safe-boundary backup/restore (spec §17.2) against a fake MAPI server. */
class WorldBackupTest {

    private static final String TOKEN = "backup-token-0123456789";

    /** Fake MAPI whose world phase is controllable; shutdown always accepted. */
    private static FakeApi startFake(AtomicReference<String> phase) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/api/v1", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            int status;
            if (auth == null || !auth.equals("Bearer " + TOKEN)) {
                status = 401;
                body = "{\"error\":{\"code\":\"UNAUTHORIZED\"}}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/server/world")) {
                status = 200;
                body = ("{\"phase\":\"" + phase.get() + "\"}").getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/process/shutdown")
                    && "POST".equals(exchange.getRequestMethod())) {
                phase.set("NONE"); // graceful stop takes effect
                status = 200;
                body = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
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
        return new FakeApi(server, phase);
    }

    private record FakeApi(HttpServer server, AtomicReference<String> phase) {
        int port() {
            return server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    @Test
    void backupWaitsForStoppedBoundaryThenCopies() throws Exception {
        AtomicReference<String> phase = new AtomicReference<>("ACTIVE");
        FakeApi api = startFake(phase);
        try {
            Path world = Files.createTempDirectory("mapi-world");
            Files.writeString(world.resolve("level.dat"), "somedata");
            Files.createDirectories(world.resolve("region"));
            Files.writeString(world.resolve("region/r.0.0.mca"), "chunkdata");
            Path backup = Files.createTempDirectory("mapi-backup");

            WorldBackup.Result result = new WorldBackup(
                    new MapiClient("http://127.0.0.1:" + api.port(), TOKEN))
                    .backup(world, backup, 10);
            assertTrue(result.ok(), result.detail());
            assertTrue(Files.isRegularFile(backup.resolve("level.dat")));
            assertTrue(Files.isRegularFile(backup.resolve("region/r.0.0.mca")));
            assertTrue(Files.isRegularFile(backup.resolve("backup-manifest.json")));
            assertTrue(result.manifest().get("consistencyScope").toString()
                    .contains("NOT quiesced"));
        } finally {
            api.stop();
        }
    }

    @Test
    void backupFailsWhenShutdownIsRefused() throws Exception {
        // Phase stays ACTIVE and the fake refuses shutdown: the backup must
        // not touch any file (no live backup, spec §17.2).
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        server.createContext("/api/v1", exchange -> {
            byte[] body;
            int status;
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v1/process/shutdown")) {
                status = 503;
                body = "{\"error\":{\"code\":\"CAPABILITY_UNAVAILABLE\"}}"
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                body = "{\"phase\":\"ACTIVE\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            Path world = Files.createTempDirectory("mapi-world");
            Files.writeString(world.resolve("level.dat"), "precious");
            Path backup = Files.createTempDirectory("mapi-backup");
            WorldBackup.Result result = new WorldBackup(
                    new MapiClient("http://127.0.0.1:" + server.getAddress().getPort(), TOKEN))
                    .backup(world, backup, 1);
            assertTrue(!result.ok());
            assertTrue(result.detail().contains("refused"));
            assertTrue(Files.isRegularFile(world.resolve("level.dat")));
            assertTrue(!Files.exists(backup.resolve("level.dat")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restoreRefusesWhileServerRunsAndRestoresWhenStopped() throws Exception {
        AtomicReference<String> phase = new AtomicReference<>("ACTIVE");
        FakeApi api = startFake(phase);
        try {
            Path world = Files.createTempDirectory("mapi-world");
            Path backup = Files.createTempDirectory("mapi-backup");
            Files.writeString(backup.resolve("level.dat"), "restored-data");
            Files.writeString(backup.resolve("backup-manifest.json"), "{}");

            MapiClient client = new MapiClient("http://127.0.0.1:" + api.port(), TOKEN);
            WorldBackup.Result refused = new WorldBackup(client).restore(backup, world);
            assertTrue(!refused.ok(), "server ACTIVE: restore must refuse");

            phase.set("NONE");
            WorldBackup.Result ok = new WorldBackup(client).restore(backup, world);
            assertTrue(ok.ok(), ok.detail());
            assertEquals("restored-data", Files.readString(world.resolve("level.dat")));
            assertTrue(!Files.exists(world.resolve("backup-manifest.json")),
                    "the manifest itself is not restored");
        } finally {
            api.stop();
        }
    }

    @Test
    void missingDirectoriesAreReportedNotCreated() throws Exception {
        AtomicReference<String> phase = new AtomicReference<>("NONE");
        FakeApi api = startFake(phase);
        try {
            MapiClient client = new MapiClient("http://127.0.0.1:" + api.port(), TOKEN);
            WorldBackup.Result missingWorld = new WorldBackup(client)
                    .backup(Path.of("/nonexistent/mapi/world"),
                            Files.createTempDirectory("mapi-b"), 1);
            assertTrue(!missingWorld.ok());
            WorldBackup.Result missingBackup = new WorldBackup(client)
                    .restore(Path.of("/nonexistent/mapi/backup"),
                            Files.createTempDirectory("mapi-w"));
            assertTrue(!missingBackup.ok());
        } finally {
            api.stop();
        }
    }
}
