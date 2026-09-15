package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI drift guard: the hand-maintained contract (docs/openapi.yaml,
 * canonical by design — SDKs generate from it) must match the actual route
 * table in both directions. A route missing from the spec is a doc bug; a
 * spec path with no route is a broken contract.
 */
class OpenApiDriftTest {

    /** Resolves docs/openapi.yaml regardless of the test working directory. */
    private static Path openapiFile() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4; i++) {
            Path candidate = dir.resolve("docs/openapi.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
            if (dir == null) {
                break;
            }
        }
        return Path.of("docs/openapi.yaml");
    }

    private static final Path OPENAPI = openapiFile();

    /** Extracts declared `paths:` entries (only /api/v1 routes). */
    private static Set<String> declaredPaths(String yaml) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("^  (/api/v1/[^:]*):$", Pattern.MULTILINE)
                .matcher(yaml);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    /** @return true when the YAML block for the path declares the method */
    private static boolean hasMethod(String yaml, String path, String method) {
        int at = yaml.indexOf("\n  " + path + ":\n");
        if (at < 0) {
            return false;
        }
        int nextPath = yaml.indexOf("\n  /api/v1/", at + 1);
        if (nextPath < 0) {
            nextPath = yaml.length();
        }
        return yaml.substring(at, nextPath)
                .contains("\n    " + method + ":");
    }

    @Test
    void specAndRoutesAgree() throws IOException {
        assertTrue(Files.isRegularFile(OPENAPI),
                "docs/openapi.yaml must exist (run from the common module)");
        String yaml = Files.readString(OPENAPI, StandardCharsets.UTF_8);

        HttpApiServer server = driftServer();
        try {
            Set<String> specPaths = declaredPaths(yaml);
            Set<String> routePaths = new LinkedHashSet<>();
            routePaths.addAll(server.getRoutePaths());
            routePaths.addAll(server.postRoutePaths());

            Set<String> undeclared = new LinkedHashSet<>(routePaths);
            undeclared.removeAll(specPaths);
            assertTrue(undeclared.isEmpty(),
                    "routes missing from docs/openapi.yaml (add them): " + undeclared);

            Set<String> unroutable = new LinkedHashSet<>(specPaths);
            unroutable.removeAll(routePaths);
            // /api/v1/jobs/{id} is a prefix route (path parameter), handled
            // before the 404 fallback; it is routable but has no literal key.
            unroutable.remove("/api/v1/jobs/{id}");
            assertTrue(unroutable.isEmpty(),
                    "spec paths without a route (implement or remove): " + unroutable);

            // Method blocks must match the route method per path.
            for (String path : server.getRoutePaths()) {
                assertTrue(hasMethod(yaml, path, "get"),
                        "spec declares no GET for " + path);
            }
            for (String path : server.postRoutePaths()) {
                assertTrue(hasMethod(yaml, path, "post"),
                        "spec declares no POST for " + path);
            }
        } finally {
            server.stop();
        }
    }

    /** A started server without any runtime lifecycle driving (routes only). */
    private static HttpApiServer driftServer() {
        var config = new dev.example.mapi.internal.config.MapiConfig(
                true, 0, "drift-token-0123456789", 10_000);
        var runtime = new dev.example.mapi.internal.MapiRuntime(
                new dev.example.mapi.internal.MapiRuntimeTest.TestPlatform(
                        org.slf4j.LoggerFactory.getLogger("drift")));
        HttpApiServer server = new HttpApiServer(config, runtime,
                org.slf4j.LoggerFactory.getLogger("drift"));
        assertTrue(server.start());
        return server;
    }
}
