package dev.example.mapi.mcp;

import dev.example.mapi.harness.DiscoveryScanner;
import dev.example.mapi.internal.json.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client binding between the MCP adapter and one MAPI instance. The
 * target resolves either from an explicit URL + token file or from the
 * instance's discovery file (spec §9); credentials are read from the
 * instance's own token file and never logged.
 */
public final class InstanceClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String token;
    private final Path gameDir;

    private InstanceClient(String baseUrl, String token, Path gameDir) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
        this.gameDir = gameDir;
    }

    /**
     * Resolves the instance from explicit arguments.
     *
     * @param baseUrl   API base URL ({@code http://127.0.0.1:port})
     * @param tokenFile bearer-token file
     * @return the client
     * @throws IOException when the token file is missing or unreadable
     */
    public static InstanceClient fromUrl(String baseUrl, Path tokenFile) throws IOException {
        return new InstanceClient(baseUrl, readToken(tokenFile), null);
    }

    /**
     * Resolves the instance from its game directory via the discovery file.
     *
     * @param gameDir instance game directory
     * @return the client
     * @throws IOException when discovery or the token file is missing
     */
    public static InstanceClient fromGameDir(Path gameDir) throws IOException {
        Optional<DiscoveryScanner.DiscoveryRecord> discovery = DiscoveryScanner.scan(gameDir);
        if (discovery.isEmpty()) {
            throw new IOException("no discovery file under " + gameDir + "/mcapi — is the instance running "
                    + "with the API enabled?");
        }
        String url = "http://127.0.0.1:" + discovery.get().apiPort();
        return new InstanceClient(url, readToken(gameDir.resolve("mcapi").resolve("token")), gameDir);
    }

    private static String readToken(Path tokenFile) throws IOException {
        if (!Files.isRegularFile(tokenFile)) {
            throw new IOException("token file missing: " + tokenFile);
        }
        String token = Files.readString(tokenFile).trim();
        if (token.isEmpty()) {
            throw new IOException("token file is empty: " + tokenFile);
        }
        return token;
    }

    /**
     * @return the instance game directory when discovery-based, else null
     */
    public Path gameDir() {
        return gameDir;
    }

    /**
     * Performs an authenticated GET.
     *
     * @return (status, body)
     */
    public Map.Entry<Integer, String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return Map.entry(response.statusCode(), response.body());
    }

    /**
     * Performs an authenticated POST with a JSON body.
     *
     * @return (status, body)
     */
    public Map.Entry<Integer, String> post(String path, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return Map.entry(response.statusCode(), response.body());
    }

    /**
     * Performs an authenticated DELETE.
     *
     * @return (status, body)
     */
    public Map.Entry<Integer, String> delete(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .DELETE().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return Map.entry(response.statusCode(), response.body());
    }

    /**
     * Polls a task until it reaches a terminal state or the budget expires.
     *
     * @param taskId    task id
     * @param timeoutMs total budget
     * @return the last seen task JSON
     */
    public String waitTask(String taskId, long timeoutMs) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "{}";
        while (System.currentTimeMillis() < deadline) {
            Map.Entry<Integer, String> response = get("/api/v1/tasks/" + taskId);
            last = response.getValue();
            if (isTerminalTask(last)) {
                return last;
            }
            Thread.sleep(200);
        }
        return last;
    }

    private static boolean isTerminalTask(String taskJson) {
        try {
            Object parsed = JsonParser.parse(taskJson);
            if (parsed instanceof Map<?, ?> task && task.get("state") instanceof String state) {
                return java.util.Set.of("succeeded", "failed", "cancelled", "expired").contains(state);
            }
        } catch (RuntimeException ignored) {
            // Fall through to non-terminal.
        }
        return false;
    }

    /**
     * Reads a screenshot artifact from the instance's controlled screenshots
     * path (path-traversal safe: file names only, fixed directory).
     *
     * @param name file name (no separators)
     * @return (base64 content, size) or null when absent
     */
    public Map.Entry<String, Integer> readScreenshot(String name) throws IOException {
        if (gameDir == null || name == null || !name.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        Path file = gameDir.resolve("mcapi").resolve("screenshots").resolve(name).normalize();
        if (!file.startsWith(gameDir.resolve("mcapi").resolve("screenshots")) || !Files.isRegularFile(file)) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        return Map.entry(Base64.getEncoder().encodeToString(bytes), bytes.length);
    }

    /**
     * JSON helper for tool results.
     *
     * @param value JSON-serializable value
     * @return compact JSON text
     */
    public static String json(Object value) {
        return dev.example.mapi.internal.json.JsonWriter.write(value);
    }

    /**
     * Parses a JSON object payload.
     *
     * @param text JSON text
     * @return the parsed map or an empty map for non-objects
     */
    public static Map<String, Object> parseObject(String text) {
        try {
            Object parsed = JsonParser.parse(text);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
        } catch (RuntimeException ignored) {
            // Treated as empty by the caller.
        }
        return new LinkedHashMap<>();
    }
}
