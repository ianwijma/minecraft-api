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
    void failedBindDoesNotPersistEnablementAndCanBeRetried() throws Exception {
        int port;
        try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = occupied.getLocalPort();
            initialize(port);
            assertEquals(0, execute("enable"));
            assertFalse(runtime.httpRunning());
            assertFalse(runtime.httpConnection().enabled());
            assertEquals(0, platform.saves);
        }
        assertEquals(1, execute("enable"));
        assertTrue(runtime.httpRunning());
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
        public void saveHttpEnabled(boolean enabled) {
            if (failSave) {
                throw new MapiConfigException("simulated write failure");
            }
            MapiJsonConfigFile.saveHttpEnabled(configDir, enabled);
            saves++;
        }
    }
}
