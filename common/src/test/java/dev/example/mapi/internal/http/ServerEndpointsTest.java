package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.encoding.TagType;
import dev.example.mapi.internal.query.WorldQueryBackend;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contract tests for the operation surface (spec §5–§14) against a real
 * loopback listener with a fake loader bridge. No Minecraft classes.
 */
class ServerEndpointsTest {

    private static final Logger LOG = LoggerFactory.getLogger(ServerEndpointsTest.class);
    private static final String TOKEN = "test-token-0123456789";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private BridgedPlatform platform;
    private MapiRuntime runtime;
    private HttpApiServer server;
    private int port;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private void startServer(dev.example.mapi.internal.operation.Scope... scopes) throws Exception {
        platform = new BridgedPlatform(LOG);
        runtime = new MapiRuntime(platform);
        MapiConfig config = scopes.length == 0
                ? new MapiConfig(true, freePort(), TOKEN, 10_000)
                : new MapiConfig(true, freePort(), TOKEN, 10_000, Set.of(scopes));
        server = new HttpApiServer(config, runtime, LOG);
        assertTrue(server.start());
        port = config.httpPort();
        // Drive the server lifecycle so the runtime wires bridge services and
        // the world session reaches ACTIVE.
        platform.lifecycleListener().onServerStarting(inlineHandle());
        platform.lifecycleListener().onServerStarted();
    }

    private static MapiRuntimeTest.TestServerHandle inlineHandle() {
        return MapiRuntimeTest.TestServerHandle.inline();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Polls a job view until it reaches a terminal state (bounded). */
    private String waitForJob(String jobId, String expectedState) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            HttpResponse<String> response = get("/api/v1/jobs/" + jobId);
            assertEquals(200, response.statusCode(), response.body());
            if (response.body().contains("\"state\":\"" + expectedState + "\"")) {
                return response.body();
            }
            if (response.body().contains("SUCCEEDED") || response.body().contains("FAILED")
                    || response.body().contains("CANCELLED")) {
                return response.body();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("job " + jobId + " did not reach " + expectedState);
    }

    @Test
    void worldInfoExposesLifecycleAndCapabilities() throws Exception {
        startServer();
        HttpResponse<String> response = get("/api/v1/server/world");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"phase\":\"ACTIVE\""), response.body());
        assertTrue(response.body().contains("\"tickControl\":true"), response.body());
        assertTrue(response.body().contains("\"worldQueries\":true"), response.body());
        assertTrue(response.body().contains("\"commands\":true"), response.body());
        assertTrue(response.body().contains("server.tick-control"), response.body());
        assertTrue(response.body().contains("\"clocks\""), response.body());
    }

    @Test
    void operationsEndpointListsRegistry() throws Exception {
        startServer();
        HttpResponse<String> response = get("/api/v1/operations");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("server.ticks.freeze"), response.body());
        assertTrue(response.body().contains("server.commands.dispatch"), response.body());
        assertTrue(response.body().contains("\"sideEffectClass\":\"unrestricted\""),
                response.body());
    }

    @Test
    void tickLifecycleOverHttp() throws Exception {
        startServer();

        // Acquire the exclusive lease.
        HttpResponse<String> lease = post("/api/v1/server/ticks/lease",
                "{\"ttlSeconds\":300}");
        assertEquals(200, lease.statusCode(), lease.body());
        String leaseId = extract(lease.body(), "leaseId");
        assertNotEquals("", leaseId);

        // Ops without a lease id fail with LEASE_REQUIRED.
        HttpResponse<String> noLease = post("/api/v1/server/ticks/freeze", "{}");
        assertEquals(409, noLease.statusCode());
        assertTrue(noLease.body().contains("LEASE_REQUIRED"), noLease.body());

        // Freeze reports state.
        HttpResponse<String> freeze = post("/api/v1/server/ticks/freeze",
                "{\"leaseId\":\"" + leaseId + "\"}");
        assertEquals(200, freeze.statusCode(), freeze.body());
        assertTrue(freeze.body().contains("\"frozen\":true"), freeze.body());

        // State endpoint agrees.
        HttpResponse<String> state = get("/api/v1/server/ticks");
        assertTrue(state.body().contains("\"frozen\":true"), state.body());
        assertTrue(state.body().contains("\"leaseId\":\"" + leaseId + "\""), state.body());

        // Rate within bounds.
        HttpResponse<String> rate = post("/api/v1/server/ticks/rate",
                "{\"leaseId\":\"" + leaseId + "\",\"rate\":40}");
        assertEquals(200, rate.statusCode(), rate.body());
        assertTrue(rate.body().contains("\"appliedTickRate\":40.0"), rate.body());
        HttpResponse<String> outOfBounds = post("/api/v1/server/ticks/rate",
                "{\"leaseId\":\"" + leaseId + "\",\"rate\":1000}");
        assertEquals(400, outOfBounds.statusCode());
        assertTrue(outOfBounds.body().contains("BAD_REQUEST"), outOfBounds.body());

        // Step runs as a job.
        HttpResponse<String> step = post("/api/v1/server/ticks/step",
                "{\"leaseId\":\"" + leaseId + "\",\"ticks\":5}");
        assertEquals(202, step.statusCode(), step.body());
        String jobId = extract(step.body(), "jobId");
        String jobView = waitForJob(jobId, "SUCCEEDED");
        assertTrue(jobView.contains("\"completed\":5"), jobView);

        // Step-and-observe captures a snapshot at the completion boundary.
        HttpResponse<String> stepObserve = post("/api/v1/server/ticks/step-and-observe",
                "{\"leaseId\":\"" + leaseId + "\",\"ticks\":3,\"label\":\"after-drop\"}");
        assertEquals(202, stepObserve.statusCode(), stepObserve.body());
        String observeJob = waitForJob(extract(stepObserve.body(), "jobId"), "SUCCEEDED");
        assertTrue(observeJob.contains("snapshotId"), observeJob);
        String snapshotId = extract(observeJob, "snapshotId");

        // Two snapshots diff cleanly.
        HttpResponse<String> second = post("/api/v1/server/snapshots",
                "{\"label\":\"later\"}");
        assertEquals(200, second.statusCode(), second.body());
        String secondId = extract(second.body(), "snapshotId");
        HttpResponse<String> diff = post("/api/v1/server/snapshot-diffs",
                "{\"firstId\":\"" + snapshotId + "\",\"secondId\":\"" + secondId + "\"}");
        assertEquals(200, diff.statusCode(), diff.body());
        // Only the label differs between the two captures.
        assertTrue(diff.body().contains("\"kind\":\"changed\""), diff.body());
        assertTrue(diff.body().contains("\"path\":\"label\""), diff.body());

        // Unfreeze closes the loop.
        HttpResponse<String> unfreeze = post("/api/v1/server/ticks/unfreeze",
                "{\"leaseId\":\"" + leaseId + "\"}");
        assertEquals(200, unfreeze.statusCode(), unfreeze.body());
        assertTrue(unfreeze.body().contains("\"frozen\":false"), unfreeze.body());
    }

    @Test
    void queriesServeBoundedData() throws Exception {
        startServer();
        assertEquals(200, get("/api/v1/server/queries/players?max=10").statusCode());
        assertTrue(get("/api/v1/server/queries/players?max=10").body().contains("\"players\""));
        assertEquals(200, get("/api/v1/server/queries/entities?dimension=minecraft:overworld"
                + "&x=0&y=64&z=0&radius=32&max=50").statusCode());
        assertEquals(200, get("/api/v1/server/queries/block?dimension=minecraft:overworld"
                + "&x=1&y=2&z=3").statusCode());
        HttpResponse<String> registries = get("/api/v1/server/queries/registries");
        assertTrue(registries.body().contains("minecraft:item"), registries.body());
        HttpResponse<String> entries = get("/api/v1/server/queries/registry"
                + "?registryId=minecraft:item&max=100");
        assertTrue(entries.body().contains("minecraft:stone"), entries.body());
        HttpResponse<String> unknown = get("/api/v1/server/queries/registry"
                + "?registryId=minecraft:missing");
        assertEquals(200, unknown.statusCode());
        assertTrue(unknown.body().contains("\"entries\":[]"), unknown.body());
    }

    @Test
    void commandDispatchSucceedsWithFullScopes() throws Exception {
        startServer();
        HttpResponse<String> response = post("/api/v1/server/commands",
                "{\"command\":\"say hi\"}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"dispatched\":true"), response.body());
    }

    @Test
    void commandDispatchRequiresTheUnrestrictedScope() throws Exception {
        startServer(dev.example.mapi.internal.operation.Scope.SERVER_TICK_CONTROL);
        HttpResponse<String> response = post("/api/v1/server/commands",
                "{\"command\":\"say hi\"}");
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("INSUFFICIENT_SCOPE"), response.body());
        assertTrue(response.body().contains("operations:unrestricted"), response.body());
    }

    @Test
    void tickOperationsRequireTheirScope() throws Exception {
        startServer(dev.example.mapi.internal.operation.Scope.CLIENT_SETTINGS);
        HttpResponse<String> response = post("/api/v1/server/ticks/lease",
                "{\"ttlSeconds\":60}");
        assertEquals(403, response.statusCode(), response.body());
        assertTrue(response.body().contains("INSUFFICIENT_SCOPE"), response.body());
    }

    @Test
    void unsupportedExecutionModeIsRejectedWithoutFallback() throws Exception {
        startServer();
        HttpResponse<String> lease = post("/api/v1/server/ticks/lease", "{\"ttlSeconds\":60}");
        String leaseId = extract(lease.body(), "leaseId");
        HttpResponse<String> response = post("/api/v1/server/ticks/step",
                "{\"leaseId\":\"" + leaseId + "\",\"ticks\":1,\"executionMode\":\"raw-input\"}");
        assertEquals(422, response.statusCode(), response.body());
        assertTrue(response.body().contains("EXECUTION_MODE_UNSUPPORTED"), response.body());
        assertTrue(response.body().contains("\"requested\":\"raw-input\""), response.body());
    }

    @Test
    void malformedAndMissingBodiesAreRejected() throws Exception {
        startServer();
        HttpResponse<String> malformed = post("/api/v1/server/snapshots", "{bad");
        assertEquals(400, malformed.statusCode());
        assertTrue(malformed.body().contains("BAD_REQUEST"), malformed.body());

        HttpResponse<String> missingLabel = post("/api/v1/server/snapshots", "{}");
        assertEquals(400, missingLabel.statusCode());
        assertTrue(missingLabel.body().contains("label is required"), missingLabel.body());
    }

    @Test
    void postToGetOnlyEndpointIsRejected() throws Exception {
        startServer();
        HttpResponse<String> response = post("/api/v1/health", "{}");
        assertEquals(405, response.statusCode());
        assertEquals(Optional.of("GET"), response.headers().firstValue("Allow"));
    }

    @Test
    void jobViewsRoundTrip() throws Exception {
        startServer();
        HttpResponse<String> lease = post("/api/v1/server/ticks/lease", "{\"ttlSeconds\":60}");
        String leaseId = extract(lease.body(), "leaseId");
        HttpResponse<String> step = post("/api/v1/server/ticks/step",
                "{\"leaseId\":\"" + leaseId + "\",\"ticks\":2}");
        String jobId = extract(step.body(), "jobId");
        // Poll to terminal state: CI workers may be slower than the step.
        String view = waitForJob(jobId, "SUCCEEDED");
        assertTrue(view.contains("\"kind\":\"ticks.step\""), view);
        assertTrue(view.contains("\"milestones\""), view);
        assertEquals(404, get("/api/v1/jobs/job-99999").statusCode());
    }

    private static String extract(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"" + field + "\":\"([^\"]+)\"").matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /** Platform whose server bridge exposes in-memory fakes for every capability. */
    private static final class BridgedPlatform extends TestPlatform {

        private final FakeBridge bridge = new FakeBridge();

        BridgedPlatform(Logger logger) {
            super(logger);
        }

        @Override
        public dev.example.mapi.internal.server.ServerBridge serverBridge() {
            return bridge;
        }
    }

    private static final class FakeBridge implements dev.example.mapi.internal.server.ServerBridge {

        @Override
        public String bridgeId() {
            return "test-bridge";
        }

        @Override
        public Set<String> supportedCapabilities() {
            return Set.of("server.progress-detection", "server.tick-control",
                    "server.world-queries", "server.commands");
        }

        @Override
        public Optional<dev.example.mapi.internal.tick.TickControlBackend> tickControl() {
            return Optional.of(new FakeTickBackend());
        }

        @Override
        public Optional<WorldQueryBackend> worldQueries() {
            return Optional.of(new FakeQueryBackend());
        }

        @Override
        public Optional<dev.example.mapi.internal.command.CommandBackend> commands() {
            return Optional.of(command -> new dev.example.mapi.internal.command.CommandBackend.CommandResult(
                    true, true, Optional.empty(), 2));
        }
    }

    private static final class FakeTickBackend
            implements dev.example.mapi.internal.tick.TickControlBackend {

        private boolean frozen;
        private boolean sprinting;
        private float rate = 20.0f;
        private long tickCount = 100;

        @Override
        public State state() {
            return new State(frozen, sprinting, rate, tickCount, Optional.empty());
        }

        @Override
        public boolean freeze() {
            frozen = true;
            return true;
        }

        @Override
        public boolean unfreeze() {
            frozen = false;
            return true;
        }

        @Override
        public Optional<Float> setTickRate(float newRate) {
            rate = newRate;
            return Optional.of(rate);
        }

        @Override
        public StepResult step(int ticks) {
            tickCount += ticks;
            return new StepResult(ticks, ticks, tickCount);
        }

        @Override
        public Optional<StepResult> sprint(int ticks) {
            sprinting = true;
            tickCount += 0;
            return Optional.of(new StepResult(ticks, 0, tickCount));
        }

        @Override
        public boolean stopStepping() {
            return false;
        }

        @Override
        public boolean stopSprinting() {
            boolean was = sprinting;
            sprinting = false;
            return was;
        }

        @Override
        public boolean supportsStepping() {
            return true;
        }

        @Override
        public boolean supportsSprinting() {
            return true;
        }

        @Override
        public boolean supportsRate() {
            return true;
        }
    }

    private static final class FakeQueryBackend implements WorldQueryBackend {

        @Override
        public List<PlayerRecord> players(int max) {
            return List.of(new PlayerRecord("00000000-0000-0000-0000-000000000001",
                    "Zoe", "minecraft:overworld", 1.5, 64.0, 2.5,
                    List.of(new ItemRecord(0, "minecraft:stone", 64))));
        }

        @Override
        public List<EntityRecord> entities(String dimension, double cx, double cy, double cz,
                int radius, int max) {
            return List.of(new EntityRecord("00000000-0000-0000-0000-0000000000ff",
                    "minecraft:creeper", dimension, cx + 1, cy, cz));
        }

        @Override
        public Optional<BlockRecord> block(String dimension, int x, int y, int z) {
            return Optional.of(new BlockRecord("minecraft:furnace",
                    Optional.of("minecraft:furnace"),
                    Optional.of(new Tag.CompoundTag(Map.of("burn",
                            new Tag.IntTag(TagType.INT, 1))))));
        }

        @Override
        public List<RegistrySummary> registries() {
            return List.of(new RegistrySummary("minecraft:item", 2),
                    new RegistrySummary("minecraft:block", 1));
        }

        @Override
        public List<String> registryEntries(String registryId, int max) {
            return "minecraft:item".equals(registryId)
                    ? List.of("minecraft:stone", "minecraft:apple")
                    : List.of();
        }

        @Override
        public long serverTickCount() {
            return 1234;
        }

        @Override
        public boolean canQueryDimension(String dimension) {
            return "minecraft:overworld".equals(dimension);
        }
    }
}
