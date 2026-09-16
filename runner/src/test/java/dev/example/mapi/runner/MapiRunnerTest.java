package dev.example.mapi.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Runner contract tests against a fake MAPI HTTP server (real loopback
 * socket; the runner speaks only the documented HTTP surface).
 */
class MapiRunnerTest {

    private static final String TOKEN = "runner-token-0123456789";

    /** Starts a fake MAPI API: health/info/world + POST snapshots. */
    private static FakeApi startFake() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        AtomicReference<String> worldPhase = new AtomicReference<>("NONE");
        server.createContext("/api/v1", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body;
            int status;
            if (auth == null || !auth.equals("Bearer " + TOKEN)) {
                status = 401;
                body = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"no\"}}".getBytes(
                        StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/health")) {
                status = 200;
                body = "{\"protocolVersion\":1,\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/info")) {
                status = 200;
                body = ("{\"protocolVersion\":1,\"name\":\"mapi\",\"version\":\"0.1.0\"}")
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/server/world")) {
                status = 200;
                body = ("{\"phase\":\"" + worldPhase.get() + "\"}").getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/api/v1/server/snapshots") && "POST".equals(exchange.getRequestMethod())) {
                status = 200;
                body = ("{\"snapshotId\":\"snap-" + System.nanoTime() + "\",\"boundary\":7}")
                        .getBytes(StandardCharsets.UTF_8);
            } else {
                status = 404;
                body = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"unknown\"}}"
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return new FakeApi(server, worldPhase);
    }

    private record FakeApi(HttpServer server, AtomicReference<String> worldPhase) {
        int port() {
            return server.getAddress().getPort();
        }

        void activateWorld() {
            worldPhase.set("ACTIVE");
        }

        void stop() {
            server.stop(0);
        }
    }

    @Test
    void versionPrintsMachineReadableJson() {
        assertEquals(0, MapiRunner.run(new String[] {"version"}, Map.of()));
    }

    @Test
    void missingTokenIsAUsageError() {
        assertEquals(2, MapiRunner.run(new String[] {"status", "--base", "http://127.0.0.1:1"},
                Map.of()));
    }

    @Test
    void statusReportsEndpointHealth() throws Exception {
        FakeApi api = startFake();
        try {
            int code = MapiRunner.run(new String[] {
                    "status", "--base", "http://127.0.0.1:" + api.port(),
                    "--token", "RUNNER_TEST_TOKEN"}, envWithToken());
            assertEquals(0, code);
        } finally {
            api.stop();
        }
    }

    @Test
    void waitWorldSucceedsOnceTheWorldActivates() throws Exception {
        FakeApi api = startFake();
        try {
            Thread activator = new Thread(() -> {
                try {
                    Thread.sleep(300);
                    api.activateWorld();
                } catch (InterruptedException ignored) {
                    // teardown
                }
            });
            activator.start();
            int code = MapiRunner.run(new String[] {
                    "wait-world", "--base", "http://127.0.0.1:" + api.port(),
                    "--token", "RUNNER_TEST_TOKEN", "--timeout", "10"}, envWithToken());
            activator.join();
            assertEquals(0, code);
        } finally {
            api.stop();
        }
    }

    @Test
    void waitWorldTimesOutWhenNothingActivates() throws Exception {
        FakeApi api = startFake();
        try {
            int code = MapiRunner.run(new String[] {
                    "wait-world", "--base", "http://127.0.0.1:" + api.port(),
                    "--token", "RUNNER_TEST_TOKEN", "--timeout", "1"}, envWithToken());
            assertEquals(1, code);
        } finally {
            api.stop();
        }
    }

    @Test
    void planRunsStepsAndStopsOnFirstFailure() throws Exception {
        FakeApi api = startFake();
        api.activateWorld();
        try {
            java.nio.file.Path plan = java.nio.file.Files.createTempFile("mapi-plan", ".json");
            java.nio.file.Files.writeString(plan, """
                    {"name":"smoke","steps":[
                      {"name":"health","method":"GET","path":"/api/v1/health"},
                      {"name":"capture","method":"POST","path":"/api/v1/server/snapshots",
                       "body":"{\\"label\\":\\"runner\\"}"},
                      {"name":"must-fail","method":"GET","path":"/api/v1/missing",
                       "expect":{"status":200}}
                    ]}
                    """);
            int code = MapiRunner.run(new String[] {
                    "run", "--base", "http://127.0.0.1:" + api.port(),
                    "--token", "RUNNER_TEST_TOKEN", "--plan", plan.toString()}, envWithToken());
            assertEquals(1, code, "the third step expects 200 but the fake returns 404");
            java.nio.file.Files.deleteIfExists(plan);
        } finally {
            api.stop();
        }
    }

    @Test
    void planPassesWhenExpectationsMatch() throws Exception {
        FakeApi api = startFake();
        api.activateWorld();
        try {
            java.nio.file.Path plan = java.nio.file.Files.createTempFile("mapi-plan", ".json");
            java.nio.file.Files.writeString(plan, """
                    {"name":"smoke","steps":[
                      {"name":"health","method":"GET","path":"/api/v1/health"},
                      {"name":"capture","method":"POST","path":"/api/v1/server/snapshots",
                       "body":"{\\"label\\":\\"runner\\"}"}
                    ]}
                    """);
            int code = MapiRunner.run(new String[] {
                    "run", "--base", "http://127.0.0.1:" + api.port(),
                    "--token", "RUNNER_TEST_TOKEN", "--plan", plan.toString()}, envWithToken());
            assertEquals(0, code);
            java.nio.file.Files.deleteIfExists(plan);
        } finally {
            api.stop();
        }
    }

    @Test
    void provisionRefusesWithoutExplicitEulaAcceptance() {
        assertEquals(3, MapiRunner.run(new String[] {"provision-server"}, Map.of()));
        assertEquals(3, MapiRunner.run(
                new String[] {"provision-server", "--accept-eula", "false"}, Map.of()));
    }

    @Test
    void provisionEulaGateOpensWithTheFlag() {
        assertEquals(1, MapiRunner.run(new String[] {"provision-server", "--accept-eula", "true"},
                Map.of()), "gate open; provisioning itself lands in a later chunk");
    }

    private static Map<String, String> envWithToken() {
        Map<String, String> env = new HashMap<>();
        env.put("RUNNER_TEST_TOKEN", TOKEN);
        return env;
    }
}
