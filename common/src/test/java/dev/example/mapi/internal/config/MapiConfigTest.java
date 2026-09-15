package dev.example.mapi.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.config.MapiTokens;
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
    void enabledWithoutTokenStartsAuthOff() {
        // Blank token is now an explicit operator choice: auth disabled with
        // a loud warning, not a startup refusal.
        MapiConfig config = load(Map.of("MAPI_HTTP_ENABLED", "true"));
        assertTrue(config.httpEnabled());
        assertTrue(!config.authRequired());
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

    @Test
    void blankTokenDisablesAuthInsteadOfRefusingStart() {
        MapiConfig config = new MapiConfig(true, 20000, "", 60);
        assertTrue(config.httpEnabled());
        assertTrue(!config.authRequired(), "blank token = auth explicitly disabled");
    }

    @Test
    void generatedTokensSatisfyTheAuthRequirement() {
        MapiConfig config = new MapiConfig(true, 20000, MapiTokens.generate(), 60);
        assertTrue(config.authRequired());
    }

    @Test
    void absentScopesGrantEverything() {
        MapiConfig config = new MapiConfig(true, 20000, "tok-0123456789abcdef", 60);
        assertEquals(java.util.EnumSet.allOf(dev.example.mapi.internal.operation.Scope.class),
                config.grantedScopes("tok-0123456789abcdef"));
        assertTrue(config.grantedScopes("wrong-token").isEmpty());
        assertTrue(config.grantedScopes(null).isEmpty());
    }

    @Test
    void configuredScopesRestrictGrants() throws IOException {
        writeFile("http.enabled=true\nhttp.token=1234567890abcdefgh\n"
                + "http.scopes=server:tick-control, client:connect\n");
        MapiConfig config = load(Map.of());
        assertEquals(
                java.util.Set.of(
                        dev.example.mapi.internal.operation.Scope.SERVER_TICK_CONTROL,
                        dev.example.mapi.internal.operation.Scope.CLIENT_CONNECT),
                config.grantedScopes("1234567890abcdefgh"));
    }

    @Test
    void unknownScopeIsRefused() {
        assertThrows(MapiConfigException.class,
                () -> load(Map.of("MAPI_HTTP_SCOPES", "server:tick-control, bogus:scope")));
    }

    @Test
    void allowlistDefaultsToDenyAll() {
        MapiConfig config = load(Map.of());
        assertTrue(config.clientConnectAllowlist().isEmpty(), "empty allowlist denies all");
        assertTrue(!config.serverLanEnabled(), "LAN publication is opt-in");
    }

    @Test
    void allowlistAndLanKeysParse() throws IOException {
        writeFile("http.enabled=true\nhttp.token=1234567890abcdefgh\n"
                + "client.connect.allowlist=127.0.0.1:25565, localhost\n"
                + "server.lan.enabled=true\n");
        MapiConfig config = load(Map.of());
        assertEquals(java.util.List.of("127.0.0.1:25565", "localhost"),
                config.clientConnectAllowlist());
        assertTrue(config.serverLanEnabled());
    }

    @Test
    void malformedAllowlistEntryIsRefused() {
        assertThrows(MapiConfigException.class,
                () -> load(Map.of("MAPI_CLIENT_CONNECT_ALLOWLIST", "bad host!:99999")));
    }

    @Test
    void envScopesOverrideFile() throws IOException {
        writeFile("http.scopes=client:settings\n");
        MapiConfig config = load(Map.of(
                "MAPI_HTTP_TOKEN", "1234567890abcdefgh",
                "MAPI_HTTP_SCOPES", "server:publish"));
        assertEquals(
                java.util.Set.of(dev.example.mapi.internal.operation.Scope.SERVER_PUBLISH),
                config.grantedScopes("1234567890abcdefgh"));
    }

    private void writeFile(String content) throws IOException {
        Files.writeString(configDir.resolve(MapiConfig.CONFIG_FILE_NAME), content, StandardCharsets.UTF_8);
    }
}
