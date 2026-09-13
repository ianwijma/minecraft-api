package dev.example.mapi.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class MapiConfigTest {

    private static final Logger LOG = LoggerFactory.getLogger(MapiConfigTest.class);

    @TempDir
    Path configDir;

    private MapiConfig load(Map<String, String> env) {
        return MapiConfig.load(configDir, env, LOG);
    }

    @Test
    void defaultsDisableHttp() {
        MapiConfig config = load(Map.of());
        assertFalse(config.httpEnabled());
        assertEquals(MapiConfig.DEFAULT_PORT, config.httpPort());
        assertNull(config.httpToken());
        assertEquals(MapiConfig.DEFAULT_RATE_LIMIT, config.rateLimitPerMinute());
    }

    @Test
    void envOverridesFile() throws IOException {
        writeFile("http.enabled=true\nhttp.port=20000\nhttp.token=1234567890abcdefgh\n");
        MapiConfig config = load(Map.of("MAPI_HTTP_PORT", "20001"));
        assertTrue(config.httpEnabled());
        assertEquals(20001, config.httpPort());
        assertEquals("1234567890abcdefgh", config.httpToken());
    }

    @Test
    void tokenFromEnvWhenFileMissing() {
        MapiConfig config = load(Map.of("MAPI_HTTP_ENABLED", "true", "MAPI_HTTP_TOKEN", "tok-0123456789abcdef"));
        assertTrue(config.httpEnabled());
        assertEquals("tok-0123456789abcdef", config.httpToken());
    }

    @Test
    void enabledWithoutTokenIsRefused() {
        MapiConfigException e = assertThrows(MapiConfigException.class,
                () -> load(Map.of("MAPI_HTTP_ENABLED", "true")));
        assertTrue(e.getMessage().contains("MAPI_HTTP_TOKEN"));
    }

    @Test
    void shortTokenIsRefused() {
        MapiConfigException e = assertThrows(MapiConfigException.class,
                () -> load(Map.of("MAPI_HTTP_ENABLED", "true", "MAPI_HTTP_TOKEN", "short")));
        assertTrue(e.getMessage().contains("16"));
    }

    @Test
    void tokenWithoutHttpEnabledIsAllowed() {
        MapiConfig config = load(Map.of("MAPI_HTTP_TOKEN", "short"));
        assertFalse(config.httpEnabled());
        assertEquals("short", config.httpToken());
    }

    @Test
    void invalidPortAndRateLimitAreRefused() {
        assertThrows(MapiConfigException.class, () -> load(Map.of("MAPI_HTTP_PORT", "0")));
        assertThrows(MapiConfigException.class, () -> load(Map.of("MAPI_HTTP_PORT", "70000")));
        assertThrows(MapiConfigException.class, () -> load(Map.of("MAPI_HTTP_PORT", "abc")));
        assertThrows(MapiConfigException.class, () -> load(Map.of("MAPI_HTTP_RATE_LIMIT_PER_MINUTE", "0")));
    }

    @Test
    void invalidBooleanIsRefused() {
        assertThrows(MapiConfigException.class, () -> load(Map.of("MAPI_HTTP_ENABLED", "yes-please")));
    }

    @Test
    void missingConfigFileIsFine() {
        MapiConfig config = load(Map.of());
        assertFalse(config.httpEnabled());
    }

    @Test
    void valuesAndKeysAreTrimmed() throws IOException {
        writeFile("http.enabled = true \nhttp.token=  verylongtokenvalue123\n");
        MapiConfig config = load(Map.of());
        assertTrue(config.httpEnabled());
        assertEquals("verylongtokenvalue123", config.httpToken());
    }

    private void writeFile(String content) throws IOException {
        Files.writeString(configDir.resolve(MapiConfig.CONFIG_FILE_NAME), content, StandardCharsets.UTF_8);
    }
}
