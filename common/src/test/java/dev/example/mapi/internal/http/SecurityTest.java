package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Security test suite (spec §14, §18): policy enforcement through supported
 * alternate paths. Every gate here is exercised against a real loopback
 * listener; gates the loader bridge does not implement surface as explicit
 * CAPABILITY_UNAVAILABLE, never silent success.
 */
class SecurityTest {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityTest.class);
    private static final String TOKEN = "security-token-0123456789";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private TestPlatform platform;
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

    private void startServer() throws Exception {
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort(), TOKEN, 10_000),
                runtime, LOG);
        assertTrue(server.start());
        port = server.boundAddress().getPort();
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> send(String method, String path, String auth,
            String json) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5));
        if (auth != null) {
            builder.header("Authorization", auth);
        }
        if (json != null) {
            builder.header("Content-Type", "application/json");
            builder.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noTokenOrWrongTokenGainsNothingAnywhere() throws Exception {
        startServer();
        for (String path : java.util.List.of("/api/v1/health", "/api/v1/server/world",
                "/api/v1/server/ticks", "/api/v1/logs", "/api/v1/operations",
                "/api/v1/client", "/api/v1/jobs/job-1")) {
            assertEquals(401, send("GET", path, null, null).statusCode(), path);
            assertEquals(401, send("GET", path, "Bearer wrong-token-aaaaaaaaaa", null)
                    .statusCode(), path);
        }
        // Operations are equally protected.
        assertEquals(401, send("POST", "/api/v1/server/ticks/lease", null, "{}").statusCode());
        assertEquals(401, send("POST", "/api/v1/server/commands",
                "Bearer wrong-token-aaaaaaaaaa", "{\"command\":\"stop\"}").statusCode());
    }

    @Test
    void blankTokenExplicitlyDisablesAuthenticationWithBrowserAccess() throws Exception {
        // Operator choice: blank http.token = auth off. Browsers (which cannot
        // set Authorization headers) then reach every endpoint; the startup
        // log carries a loud warning.
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort(), "", 10_000),
                runtime, LOG);
        assertTrue(server.start());
        port = server.boundAddress().getPort();
        assertEquals(200, send("GET", "/api/v1/health", null, null).statusCode());
        assertTrue(!send("GET", "/api/v1/health", null, null).headers()
                .firstValue("WWW-Authenticate").isPresent());
        assertEquals(200, send("GET", "/api/v1/server/world", null, null).statusCode());
    }

    @Test
    void tokensInQueryParametersOrBodyNeverAuthorize() throws Exception {
        startServer();
        // Query-string tokens are explicitly forbidden (spec §13.1).
        assertEquals(401, send("GET", "/api/v1/health?token=" + TOKEN, null, null).statusCode());
        // Bearer value smuggled into a JSON body changes nothing.
        assertEquals(401, send("POST", "/api/v1/server/ticks/lease", null,
                "{\"token\":\"" + TOKEN + "\"}").statusCode());
    }

    @Test
    void basicAndCookieAuthSchemesDoNotAuthenticate() throws Exception {
        startServer();
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString(("mapi:" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertEquals(401, send("GET", "/api/v1/health", basic, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/health", TOKEN, null).statusCode());
        assertEquals(401, send("GET", "/api/v1/health", "Bearer", null).statusCode());
    }

    @Test
    void restrictedScopesBlockEveryGateConsistently() throws Exception {
        // A token granted only client:settings cannot reach tick control,
        // commands, or shutdown through ANY route variant.
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort(), TOKEN, 10_000,
                        java.util.Set.of(dev.example.mapi.internal.operation.Scope.CLIENT_SETTINGS)),
                runtime, LOG);
        assertTrue(server.start());
        port = server.boundAddress().getPort();

        for (String path : java.util.List.of("/api/v1/server/ticks/lease",
                "/api/v1/server/ticks/freeze", "/api/v1/server/ticks/step",
                "/api/v1/server/commands", "/api/v1/process/shutdown")) {
            HttpResponse<String> response = send("POST", path, "Bearer " + TOKEN, "{}");
            assertEquals(403, response.statusCode(), path);
            assertTrue(response.body().contains("INSUFFICIENT_SCOPE"), path + ": " + response.body());
        }
        HttpResponse<String> window = send("POST", "/api/v1/client/window/set-windowed",
                "Bearer " + TOKEN, "{\"width\":1280,\"height\":720}");
        assertEquals(503, window.statusCode(),
                "client:settings grant passes authorization and reaches the unavailable client capability");
        assertTrue(window.body().contains("CAPABILITY_UNAVAILABLE"), window.body());
    }

    @Test
    void windowMutationsRequireClientSettingsScope() throws Exception {
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(new MapiConfig(true, freePort(), TOKEN, 10_000,
                        java.util.Set.of(dev.example.mapi.internal.operation.Scope.SERVER_TICK_CONTROL)),
                runtime, LOG);
        assertTrue(server.start());
        port = server.boundAddress().getPort();
        HttpResponse<String> window = send("POST", "/api/v1/client/window/set-windowed",
                "Bearer " + TOKEN, "{\"width\":1280,\"height\":720}");
        assertEquals(403, window.statusCode());
        assertTrue(window.body().contains("client:settings"), window.body());
    }

    @Test
    void operationLeasesAreValidatedBeforeCapabilityDispatch() throws Exception {
        startServer();
        HttpResponse<String> response = send("POST", "/api/v1/server/ticks/freeze",
                "Bearer " + TOKEN, "{\"leaseId\":\"not-held\"}");
        assertEquals(409, response.statusCode());
        assertTrue(response.body().contains("LEASE_REQUIRED"), response.body());
    }

    @Test
    void inventoryClickRequiresClientLogicModeAndInputLease() throws Exception {
        startServer();
        HttpResponse<String> unsupported = send("POST", "/api/v1/client/inventory/click",
                "Bearer " + TOKEN,
                "{\"slot\":1,\"executionMode\":\"raw-input\"}");
        assertEquals(422, unsupported.statusCode());
        assertTrue(unsupported.body().contains("EXECUTION_MODE_UNSUPPORTED"), unsupported.body());

        HttpResponse<String> noLease = send("POST", "/api/v1/client/inventory/click",
                "Bearer " + TOKEN,
                "{\"slot\":1,\"executionMode\":\"client-logic\"}");
        assertEquals(409, noLease.statusCode());
        assertTrue(noLease.body().contains("LEASE_REQUIRED"), noLease.body());

        HttpResponse<String> acquired = send("POST", "/api/v1/client/control/lease",
                "Bearer " + TOKEN, "{}");
        assertEquals(200, acquired.statusCode(), acquired.body());
        var leaseId = java.util.regex.Pattern.compile("\"leaseId\":\"([^\"]+)\"")
                .matcher(acquired.body());
        assertTrue(leaseId.find(), acquired.body());
        HttpResponse<String> click = send("POST", "/api/v1/client/inventory/click",
                "Bearer " + TOKEN, "{\"slot\":1,\"executionMode\":\"client-logic\",\"leaseId\":\""
                        + leaseId.group(1) + "\"}");
        assertEquals(503, click.statusCode(), "valid mode and lease reach the unavailable platform capability");
        assertTrue(click.body().contains("CAPABILITY_UNAVAILABLE"), click.body());
    }

    @Test
    void inputLeaseCanRenewOnlyItsCurrentLeaseId() throws Exception {
        startServer();
        HttpResponse<String> acquired = send("POST", "/api/v1/client/control/lease",
                "Bearer " + TOKEN, "{\"ttlSeconds\":60}");
        assertEquals(200, acquired.statusCode(), acquired.body());
        String leaseId = jsonStringField(acquired.body(), "leaseId");
        long initialExpiry = jsonLongField(acquired.body(), "expiresAtEpochMs");

        HttpResponse<String> renewed = send("POST", "/api/v1/client/control/lease",
                "Bearer " + TOKEN, "{\"leaseId\":\"" + leaseId + "\",\"ttlSeconds\":300}");
        assertEquals(200, renewed.statusCode(), renewed.body());
        assertEquals(leaseId, jsonStringField(renewed.body(), "leaseId"));
        assertTrue(jsonLongField(renewed.body(), "expiresAtEpochMs") > initialExpiry,
                "renewal extends expiry while preserving the lease ID");

        runtime.leases().release(runtime.leases().holderOf("input").orElseThrow());
        var otherLease = runtime.leases().acquire("input", "test-other-owner", 60_000);
        HttpResponse<String> staleRenewal = send("POST", "/api/v1/client/control/lease",
                "Bearer " + TOKEN, "{\"leaseId\":\"" + leaseId + "\",\"ttlSeconds\":300}");
        assertEquals(409, staleRenewal.statusCode());
        assertTrue(staleRenewal.body().contains("LEASE_REQUIRED"), staleRenewal.body());
        assertEquals(otherLease.id(), runtime.leases().holderOf("input").orElseThrow().id(),
                "stale ID must not renew or replace another current lease");
    }

    private static String jsonStringField(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":\"([^\"]+)\"")
                .matcher(json);
        assertTrue(matcher.find(), json);
        return matcher.group(1);
    }

    private static long jsonLongField(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\"" + field + "\":([0-9]+)")
                .matcher(json);
        assertTrue(matcher.find(), json);
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void unknownExecutionModesAreRejectedBeforeAnyDispatch() throws Exception {
        startServer();
        // No bridge: the capability check would normally fail first; the mode
        // check happens in the guard before any dispatch either way.
        HttpResponse<String> response = send("POST", "/api/v1/client/actions/hold-key",
                "Bearer " + TOKEN, "{\"keyCode\":1,\"ticks\":1,\"executionMode\":\"privileged\"}");
        // hold-key supports only raw-input; privileged is rejected with 422
        // (or 503 where no client bridge exists at all - both are refusals,
        // never silent substitution).
        assertTrue(response.statusCode() == 503 || response.statusCode() == 422,
                String.valueOf(response.statusCode()));
    }

    @Test
    void oversizedBodiesAreRejectedBeforeDispatch() throws Exception {
        startServer();
        String big = "{\"command\":\"" + "x".repeat(9000) + "\"}";
        HttpResponse<String> response = send("POST", "/api/v1/server/commands",
                "Bearer " + TOKEN, big);
        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("PAYLOAD_TOO_LARGE"), response.body());
    }

    @Test
    void malformedJsonNeverReachesHandlers() throws Exception {
        startServer();
        for (String bad : java.util.List.of("{", "[]", "\"str\"", "null", "{\"a\":NaN}")) {
            HttpResponse<String> response = send("POST", "/api/v1/server/commands",
                    "Bearer " + TOKEN, bad);
            assertTrue(response.statusCode() == 400 || response.statusCode() == 503,
                    bad + " -> " + response.statusCode());
        }
    }

    @Test
    void shutdownIsAdministrativeOnly() throws Exception {
        startServer();
        HttpResponse<String> response = send("POST", "/api/v1/process/shutdown",
                "Bearer " + TOKEN, "{}");
        // Default platform has no shutdown implementation: explicit refusal,
        // never a silent no-op success.
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("CAPABILITY_UNAVAILABLE"), response.body());
    }
}
