package dev.example.mapi.internal;

import static org.junit.jupiter.api.Assertions.*;

import dev.example.mapi.internal.command.MapiControlCommand;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.config.MapiConfigException;
import dev.example.mapi.internal.config.MapiJsonConfigFile;
import dev.example.mapi.internal.json.JsonReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MapiControlCommandTest {

    private static final Logger LOG = LoggerFactory.getLogger(MapiControlCommandTest.class);
    private static final String TOKEN = "test-command-token-0123456789";
    @TempDir Path configDir;
    private MapiRuntime runtime;
    private ConfigPlatform platform;
    private final List<String> feedback = new ArrayList<>();
    private final List<MapiRuntime.HttpConnection> connections = new ArrayList<>();

    private void initialize(int port) throws Exception {
        Files.writeString(configDir.resolve("mapi.json"),
                "{\"http.enabled\":false,\"http.port\":" + port
                        + ",\"http.token\":\"" + TOKEN + "\",\"future.option\":42}");
        platform = new ConfigPlatform();
        runtime = new MapiRuntime(platform);
        platform.clientLifecycleListener().onClientStarted();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private int execute(String action) {
        return MapiControlCommand.execute(runtime, action, feedback::add, connections::add);
    }

    @AfterEach
    void stop() {
        if (platform != null) {
            platform.clientLifecycleListener().onClientStopping();
        }
    }

    @Test
    void commandsControlRealListenerAndSurviveRestartWithoutChangingCredentials() throws Exception {
        initialize(freePort());
        assertEquals(1, execute("status"));
        assertFalse(connections.getLast().enabled());
        assertFalse(connections.getLast().running());
        assertEquals(TOKEN, connections.getLast().token());
        assertFalse(connections.getLast().toString().contains(TOKEN));
        assertEquals(0, platform.saves);

        assertEquals(1, execute("enable"));
        var first = connections.getLast();
        assertTrue(first.running());
        assertTrue(MapiJsonConfigFile.load(configDir, Map.of(), LOG).httpEnabled());
        assertEquals(1, platform.saves);
        try (HttpClient client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(first.url() + "/api/v1/health"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        }

        assertEquals(1, execute("enable"));
        assertEquals(first, connections.getLast());
        assertEquals(1, platform.saves, "enabling twice must not restart or rewrite config");
        platform.clientLifecycleListener().onClientStopping();
        runtime = new MapiRuntime(platform);
        platform.clientLifecycleListener().onClientStarted();
        assertTrue(runtime.httpRunning(), "saved enablement starts a new process runtime");
        assertEquals(TOKEN, runtime.httpConnection().token());

        assertEquals(1, execute("disable"));
        assertFalse(runtime.httpRunning());
        assertFalse(MapiJsonConfigFile.load(configDir, Map.of(), LOG).httpEnabled());
        assertEquals(1, execute("disable"));
        assertFalse(runtime.httpRunning());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        assertFalse(runtime.httpRunning(), "world lifecycle must not re-enable the listener");
        platform.clientLifecycleListener().onClientStopping();
        runtime = new MapiRuntime(platform);
        platform.clientLifecycleListener().onClientStarted();
        assertFalse(runtime.httpRunning(), "saved disablement survives restart");
        var document = (Map<?, ?>) JsonReader.parse(Files.readString(configDir.resolve("mapi.json")));
        assertEquals(42L, document.get("future.option"));
        assertEquals(TOKEN, document.get("http.token"));
        assertTrue(feedback.stream().noneMatch(text -> text.contains(TOKEN)));
    }

    @Test
    void occupiedPortRetriesAndReportsActualConnectionWithoutChangingConfiguredPort() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = occupied.getLocalPort();
            initialize(port);
            assertEquals(1, execute("enable"));
            var connection = connections.getLast();
            assertTrue(connection.running());
            assertNotEquals(port, URI.create(connection.url()).getPort());
            assertEquals(TOKEN, connection.token());
            assertEquals(1, platform.saves);
            assertEquals(port, MapiJsonConfigFile.load(configDir, Map.of(), LOG).httpPort());
            assertEquals(1, execute("status"));
            assertEquals(connection, connections.getLast());
            assertEquals(1, execute("enable"));
            assertEquals(connection, connections.getLast());
            assertEquals(1, platform.saves);
            assertTrue(feedback.isEmpty(), "successful fallback must not show an error");
            try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                var request = HttpRequest.newBuilder(URI.create(connection.url() + "/api/v1/health"))
                        .header("Authorization", "Bearer " + TOKEN).timeout(Duration.ofSeconds(5));
                assertEquals(401, client.send(HttpRequest.newBuilder(request.build().uri())
                        .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(200, client.send(request.header("Origin", connection.url()).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, client.send(request.setHeader("Origin", "http://127.0.0.1:" + port).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(1, execute("disable"));
            assertFalse(runtime.httpRunning());
            assertEquals("http://127.0.0.1:" + port, runtime.httpConnection().url());
        }
    }

    @Test
    void automaticStartupKeepsConfiguredPortButExplicitEnableCanRecover() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            initialize(occupied.getLocalPort());
            MapiJsonConfigFile.saveHttpEnabled(configDir, true);
            platform.clientLifecycleListener().onClientStopping();
            runtime = new MapiRuntime(platform);
            platform.clientLifecycleListener().onClientStarted();
            assertFalse(runtime.httpRunning());
            assertEquals(1, execute("enable"));
            assertTrue(runtime.httpRunning());
        }
    }

    @Test
    void failedSaveAfterPortRetryRollsBackAndCanBeRetried() throws Exception {
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            initialize(occupied.getLocalPort());
            platform.failSave = true;
            assertEquals(0, execute("enable"));
            assertFalse(runtime.httpRunning());
            assertFalse(runtime.httpConnection().enabled());
            assertEquals(0, platform.saves);
            platform.failSave = false;
            assertEquals(1, execute("enable"));
            assertTrue(runtime.httpRunning());
        }
    }

    @Test
    void failedSaveRollsBackEnableAndDoesNotClaimDisableSucceeded() throws Exception {
        initialize(freePort());
        platform.failSave = true;
        assertEquals(0, execute("enable"));
        assertFalse(runtime.httpRunning());
        assertFalse(runtime.httpConnection().enabled());
        platform.failSave = false;
        assertEquals(1, execute("enable"));
        platform.failSave = true;
        assertEquals(0, execute("disable"));
        assertTrue(runtime.httpRunning());
    }

    @Test
    void invalidTokenCannotEnableAndDoesNotLeakIntoFeedback() throws Exception {
        initialize(freePort());
        Files.writeString(configDir.resolve("mapi.json"),
                "{\"http.enabled\":false,\"http.token\":\"short-secret\"}");
        assertEquals(0, execute("enable"));
        assertFalse(runtime.httpRunning());
        assertEquals(0, platform.saves);
        assertTrue(feedback.stream().noneMatch(text -> text.contains("short-secret")));
    }

    private int listCommand(String list, String action, String site) {
        return MapiControlCommand.executeAllowList(runtime, list, action, site, feedback::add);
    }

    private int browserStatus(String origin, boolean preflight) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(runtime.httpConnection().url() + "/api/v1/health"))
                    .header("Origin", origin).header("Authorization", "Bearer " + TOKEN);
            if (preflight) request.method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Access-Control-Request-Method", "GET");
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 || response.statusCode() == 204) {
                assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            }
            return response.statusCode();
        }
    }

    @Test
    void browserCommandsApplyToRunningListenerAndPersistIndependently() throws Exception {
        initialize(freePort());
        assertEquals(1, execute("enable"));
        var connection = runtime.httpConnection();
        String origin = "https://new.example";
        assertEquals(403, browserStatus(origin, false));
        assertEquals(1, listCommand("allowedOrigins", "add", "HTTPS://NEW.EXAMPLE:443"));
        assertEquals(1, listCommand("allowedOrigins", "add", origin));
        assertEquals(1, runtime.config().httpAllowedOrigins().stream().filter(origin::equals).count());
        assertEquals(200, browserStatus(origin, false));
        assertEquals(204, browserStatus(origin, true));
        assertEquals(1, listCommand("allowedOrigins", "remove", origin));
        assertEquals(403, browserStatus(origin, true));
        assertEquals(1, listCommand("allowedOrigins", "disable", null));
        assertEquals(200, browserStatus(origin, false));
        assertEquals(204, browserStatus(origin, true));
        assertEquals(403, browserStatus("null", true));
        assertTrue(runtime.config().clientConnectAllowlistEnabled());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create(connection.url() + "/api/v1/health"))
                    .header("Origin", origin).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode(), "disabling origin enforcement preserves authentication");
        }
        assertEquals(connection, runtime.httpConnection(), "list commands keep the listener and credentials");
        platform.clientLifecycleListener().onClientStopping();
        runtime = new MapiRuntime(platform);
        platform.clientLifecycleListener().onClientStarted();
        assertEquals(200, browserStatus(origin, false));
        assertFalse(runtime.config().httpAllowedOriginsEnabled());
        assertEquals(1, listCommand("allowedOrigins", "enable", null));
        assertEquals(403, browserStatus(origin, false));
        var saved = MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        assertTrue(saved.httpAllowedOriginsEnabled());
        assertTrue(saved.clientConnectAllowlistEnabled());
        assertEquals(TOKEN, saved.httpToken());
        assertEquals(42L, ((Map<?, ?>) JsonReader.parse(Files.readString(configDir.resolve("mapi.json")))).get("future.option"));
    }

    @Test
    void targetCommandsWorkWithHttpOffAndSurviveHttpAndProcessRestarts() throws Exception {
        initialize(freePort());
        assertEquals(1, listCommand("allowList", "add", "PLAY.EXAMPLE:25565"));
        assertEquals(1, listCommand("allowList", "add", "play.example:25565"));
        assertEquals(1, listCommand("allowList", "add", "[2001:db8::1]:25565"));
        assertEquals(1, listCommand("allowList", "disable", null));
        assertFalse(runtime.httpRunning());
        assertEquals(1, execute("enable"));
        assertFalse(runtime.config().clientConnectAllowlistEnabled());
        assertTrue(runtime.config().httpAllowedOriginsEnabled());
        assertEquals(List.of("play.example:25565", "[2001:db8::1]:25565"), runtime.config().clientConnectAllowlist());
        assertEquals(1, execute("disable"));
        assertEquals(1, execute("enable"));
        assertFalse(runtime.config().clientConnectAllowlistEnabled());
        assertEquals(1, listCommand("allowList", "remove", "PLAY.EXAMPLE:25565"));
        assertEquals(1, listCommand("allowList", "enable", null));
        platform.clientLifecycleListener().onClientStopping();
        runtime = new MapiRuntime(platform);
        platform.clientLifecycleListener().onClientStarted();
        assertTrue(runtime.config().clientConnectAllowlistEnabled());
        assertEquals(List.of("[2001:db8::1]:25565"), runtime.config().clientConnectAllowlist());
    }

    @Test
    void invalidEntriesAndFailedSavesLeaveDiskAndRuntimeUnchanged() throws Exception {
        initialize(freePort());
        assertEquals(1, execute("enable"));
        var before = runtime.config();
        String disk = Files.readString(configDir.resolve("mapi.json"));
        for (String site : List.of("bad host!", "host:65536", "host:0", "a,b")) {
            assertEquals(0, listCommand("allowList", "add", site));
        }
        for (String site : List.of("https://example/path", "https://*.example", "null", "https://a,https://b")) {
            assertEquals(0, listCommand("allowedOrigins", "add", site));
        }
        platform.failSave = true;
        assertEquals(0, listCommand("allowedOrigins", "disable", null));
        assertEquals(0, listCommand("allowList", "add", "play.example"));
        assertEquals(before, runtime.config());
        assertEquals(disk, Files.readString(configDir.resolve("mapi.json")));
        assertTrue(feedback.stream().noneMatch(text -> text.contains(TOKEN)));
    }

    private final class ConfigPlatform extends MapiRuntimeTest.TestPlatform {
        int saves;
        boolean failSave;

        ConfigPlatform() {
            super(LOG);
        }

        @Override
        public Path configDir() {
            return configDir;
        }

        @Override
        public MapiConfig loadConfig(Path dir, Map<String, String> env, Logger logger) {
            return MapiJsonConfigFile.load(dir, env, logger);
        }

        @Override
        public void saveAllowLists(MapiConfig config) {
            if (failSave) throw new MapiConfigException("simulated write failure");
            MapiJsonConfigFile.saveAllowLists(configDir, config);
        }

        @Override
        public void saveHttpEnabled(boolean enabled) {
            if (failSave) {
                throw new MapiConfigException("simulated write failure");
            }
            MapiJsonConfigFile.saveHttpEnabled(configDir, enabled);
            saves++;
        }
    }
}
