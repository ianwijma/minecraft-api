package dev.example.mapi.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MapiJsonConfigFileTest {

    private static final Logger LOG = LoggerFactory.getLogger(MapiJsonConfigFileTest.class);

    @TempDir
    Path configDir;

    @Test
    void generatesDefaultsOnFirstRun() throws IOException {
        MapiConfig config = MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        assertFalse(config.httpEnabled());
        assertEquals(MapiConfig.DEFAULT_PORT, config.httpPort());
        assertTrue(config.clientConnectAllowlist().isEmpty());
        assertTrue(!config.serverLanEnabled());

        Path file = configDir.resolve(MapiJsonConfigFile.JSON_FILE_NAME);
        assertTrue(Files.isRegularFile(file), "generated on first run");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"http.enabled\":false"), content);
        assertTrue(content.contains("\"client.connect.allowlist\":[]"), content);
    }

    @Test
    void secondRunReadsTheGeneratedFile() throws IOException {
        MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        // Operator edits the generated file (JSON booleans/numbers native).
        Path file = configDir.resolve(MapiJsonConfigFile.JSON_FILE_NAME);
        Files.writeString(file, """
                {"http.enabled":true,"http.port":25590,"http.token":"tok-0123456789abcdef",
                 "http.rateLimitPerMinute":120,
                 "client.connect.allowlist":["127.0.0.1:25565"],
                 "server.lan.enabled":true}
                """);
        MapiConfig config = MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        assertTrue(config.httpEnabled());
        assertEquals(25590, config.httpPort());
        assertEquals("tok-0123456789abcdef", config.httpToken());
        assertEquals(java.util.List.of("127.0.0.1:25565"), config.clientConnectAllowlist());
        assertTrue(config.serverLanEnabled());
    }

    @Test
    void envOverridesWinOverFile() throws IOException {
        MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        Path file = configDir.resolve(MapiJsonConfigFile.JSON_FILE_NAME);
        Files.writeString(file, "{\"http.enabled\":false,\"http.token\":\"file-token-01234567\"}");
        MapiConfig config = MapiJsonConfigFile.load(configDir, Map.of(
                "MAPI_HTTP_ENABLED", "true",
                "MAPI_HTTP_TOKEN", "env-token-01234567890"), LOG);
        assertTrue(config.httpEnabled());
        assertEquals("env-token-01234567890", config.httpToken());
    }

    @Test
    void invalidValuesAreRefused() throws IOException {
        MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        Path file = configDir.resolve(MapiJsonConfigFile.JSON_FILE_NAME);
        Files.writeString(file, "{\"http.enabled\":true}");
        // Enabled without a token: refused by fromValues validation.
        assertThrows(MapiConfigException.class,
                () -> MapiJsonConfigFile.load(configDir, Map.of(), LOG));
        Files.writeString(file, "{\"http.port\":70000}");
        assertThrows(MapiConfigException.class,
                () -> MapiJsonConfigFile.load(configDir, Map.of(), LOG));
        Files.writeString(file, "{\"client.connect.allowlist\":[\"bad host!\"]}");
        assertThrows(MapiConfigException.class,
                () -> MapiJsonConfigFile.load(configDir, Map.of(), LOG));
        Files.writeString(file, "{\"unknown.key\":1}");
        // Unknown keys are tolerated (forward compatibility) and defaults fill in.
        MapiConfig config = MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        assertFalse(config.httpEnabled());
    }

    @Test
    void malformedJsonIsRefused() throws IOException {
        MapiJsonConfigFile.load(configDir, Map.of(), LOG);
        Path file = configDir.resolve(MapiJsonConfigFile.JSON_FILE_NAME);
        Files.writeString(file, "{not json");
        assertThrows(MapiConfigException.class,
                () -> MapiJsonConfigFile.load(configDir, Map.of(), LOG));
    }
}
