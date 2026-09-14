package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Contract-sync tripwire (spec §11/§13.2): every implemented HTTP route must
 * be documented in both {@code docs/openapi.yaml} and
 * {@code docs/http-api.md}. When this test fails, add the new endpoint to
 * those docs in the same change set (AGENTS.md §6).
 */
class ContractSyncTest {

    private static final List<String> ROUTES = List.of(
            "GET /api/v1/health",
            "GET /api/v1/live",
            "GET /api/v1/ready",
            "GET /api/v1/time",
            "GET /api/v1/info",
            "GET /api/v1/events",
            "POST /api/v1/events/ticket",
            "GET /api/v1/server/status",
            "GET /api/v1/server/players",
            "POST /api/v1/server/commands/execute",
            "GET /api/v1/server/world/block",
            "GET /api/v1/server/world/block-entity",
            "GET /api/v1/server/world/storage",
            "GET /api/v1/server/world/time",
            "GET /api/v1/client/status",
            "GET /api/v1/client/screen/tree",
            "POST /api/v1/client/input/key",
            "POST /api/v1/client/screenshot",
            "GET /api/v1/tasks",
            "POST /api/v1/tasks",
            "GET /api/v1/tasks/{id}",
            "DELETE /api/v1/tasks/{id}",
            "GET /api/v1/leases",
            "POST /api/v1/leases",
            "POST /api/v1/leases/{id}/renew",
            "DELETE /api/v1/leases/{id}",
            "GET /api/v1/registry/{type}",
            "GET /api/v1/tags/{type}",
            "GET /api/v1/mods",
            "GET /api/v1/threads",
            "POST /api/v1/memory/gc",
            "GET|POST /api/v1/ext/{id}/…",
            "GET /api/v1/files",
            "POST /api/v1/files",
            "POST /api/v1/unsafe/reflect",
            "POST /api/v1/unsafe/invoke",
            "GET /api/v1/logs",
            "GET /api/v1/logs/errors",
            "GET /api/v1/crash-reports",
            "GET /api/v1/capabilities");

    private static final Set<String> CONCRETE_PATHS = Set.of(
            "/api/v1/health", "/api/v1/live", "/api/v1/ready", "/api/v1/time", "/api/v1/info",
            "/api/v1/events", "/api/v1/events/ticket", "/api/v1/server/status", "/api/v1/server/players",
            "/api/v1/server/commands/execute", "/api/v1/server/world/block",
            "/api/v1/server/world/block-entity", "/api/v1/server/world/storage",
            "/api/v1/server/world/time", "/api/v1/client/status", "/api/v1/client/screen/tree",
            "/api/v1/client/input/key", "/api/v1/client/screenshot", "/api/v1/tasks",
            "/api/v1/tasks/{id}", "/api/v1/leases", "/api/v1/leases/{id}/renew",
            "/api/v1/registry/{type}", "/api/v1/tags/{type}", "/api/v1/ext/{id}/{path}",
            "/api/v1/mods", "/api/v1/threads",
            "/api/v1/memory/gc", "/api/v1/files", "/api/v1/unsafe/reflect", "/api/v1/unsafe/invoke",
            "/api/v1/logs", "/api/v1/logs/errors", "/api/v1/crash-reports", "/api/v1/capabilities");

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !dir.resolve("docs").resolve("openapi.yaml").toFile().exists()) {
            dir = dir.getParent();
        }
        return dir;
    }

    private static String readDocs(String name) throws java.io.IOException {
        Path root = repoRoot();
        assertTrue(root != null, "docs/openapi.yaml not found from " + Path.of("").toAbsolutePath());
        return java.nio.file.Files.readString(root.resolve("docs").resolve(name));
    }

    @Test
    void openapiDocumentsEveryConcreteRoute() throws Exception {
        String openapi = readDocs("openapi.yaml");
        List<String> documentedPaths = new ArrayList<>();
        Matcher matcher = Pattern.compile("^  (/api/v1/[A-Za-z0-9_{}/-]+):$", Pattern.MULTILINE)
                .matcher(openapi);
        while (matcher.find()) {
            documentedPaths.add(matcher.group(1));
        }
        List<String> missing = new ArrayList<>();
        for (String route : CONCRETE_PATHS) {
            if (!documentedPaths.contains(route)) {
                missing.add(route);
            }
        }
        assertTrue(missing.isEmpty(),
                "Endpoints missing from docs/openapi.yaml (add them or update ContractSyncTest): " + missing);
    }

    @Test
    void httpApiMdMentionsEveryRoute() throws Exception {
        String httpApi = readDocs("http-api.md");
        List<String> missing = new ArrayList<>();
        for (String route : ROUTES) {
            String path = route.split(" ")[1];
            if (!httpApi.contains(path)) {
                missing.add(route);
            }
        }
        assertTrue(missing.isEmpty(),
                "Endpoints missing from docs/http-api.md (add them or update ContractSyncTest): " + missing);
    }

    @Test
    void routeListIsWellFormed() {
        Map<String, List<String>> byPath = new LinkedHashMap<>();
        for (String route : ROUTES) {
            String[] parts = route.split(" ");
            assertTrue(parts.length == 2, "malformed route: " + route);
            assertTrue(parts[0].matches("GET|POST|DELETE|GET\\|POST"), "malformed method: " + route);
            byPath.computeIfAbsent(parts[1], k -> new ArrayList<>()).add(parts[0]);
        }
        assertEqualsRouteCount(byPath);
    }

    private void assertEqualsRouteCount(Map<String, List<String>> byPath) {
        assertTrue(new TreeSet<>(byPath.keySet()).size() >= 25, "route surface unexpectedly small");
    }

    /**
     * OpenAPI 3.1 pinned subset (spec §10): banned constructs must not
     * appear, and every internal {@code $ref} must resolve to a documented
     * schema. Guards the SDK-generation pipeline contract.
     */
    @Test
    void openapiStaysInPinnedSubsetWithResolvableRefs() throws Exception {
        String openapi = readDocs("openapi.yaml");
        for (String banned : new String[] {"$dynamicRef", "$dynamicAnchor", "$anchor", "$patternProperties",
                "oneOfOfOneOf"}) {
            assertFalse(openapi.contains(banned), "banned OpenAPI construct: " + banned);
        }
        Matcher refMatcher = Pattern.compile("\\$ref: \"#/components/schemas/([A-Za-z0-9_]+)\"")
                .matcher(openapi);
        List<String> missing = new ArrayList<>();
        while (refMatcher.find()) {
            String schema = refMatcher.group(1);
            if (!Pattern.compile("^    " + Pattern.quote(schema) + ":$", Pattern.MULTILINE)
                    .matcher(openapi).find()) {
                missing.add(schema);
            }
        }
        assertTrue(missing.isEmpty(), "$ref targets missing from components/schemas: " + missing);
        assertTrue(openapi.contains("openapi: 3.1"), "spec requires OpenAPI 3.1");
    }
}
