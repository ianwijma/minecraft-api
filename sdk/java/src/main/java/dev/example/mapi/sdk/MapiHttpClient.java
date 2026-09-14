package dev.example.mapi.sdk;

import dev.example.mapi.harness.DiscoveryScanner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JVM SDK for the MAPI local HTTP API (spec §10): bearer-token auth from the
 * instance's token file, discovery-based resolution, task polling with
 * idempotent creation, and event cursors with explicit gap reporting. Built
 * on the JDK HTTP client; no third-party dependencies.
 */
public final class MapiHttpClient {

    /** A protocol or transport failure with the documented error shape. */
    public static final class MapiException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** @return HTTP status (0 when the connection failed) */
        public final int status;

        /** @return the MAPI error code */
        public final String code;

        MapiException(int status, String code, String message) {
            super(status + " " + code + ": " + message);
            this.status = status;
            this.code = code;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;
    private final String token;

    private MapiHttpClient(String baseUrl, String token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.token = token;
    }

    /**
     * Resolves the instance via its discovery and token files.
     *
     * @param gameDir instance game directory
     * @return the client
     * @throws IOException when discovery or the token file is missing
     */
    public static MapiHttpClient fromGameDir(Path gameDir) throws IOException {
        Optional<DiscoveryScanner.DiscoveryRecord> discovery = DiscoveryScanner.scan(gameDir);
        if (discovery.isEmpty()) {
            throw new IOException("no discovery file under " + gameDir + "/mcapi — is the instance running "
                    + "with the API enabled?");
        }
        return new MapiHttpClient("http://127.0.0.1:" + discovery.get().apiPort(),
                readToken(gameDir.resolve("mcapi").resolve("token")));
    }

    /**
     * Resolves the instance from an explicit URL and token file.
     *
     * @param baseUrl   API base URL
     * @param tokenFile bearer-token file
     * @return the client
     * @throws IOException when the token file is missing
     */
    public static MapiHttpClient fromUrl(String baseUrl, Path tokenFile) throws IOException {
        return new MapiHttpClient(baseUrl, readToken(tokenFile));
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

    private <T> T exchange(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        HttpResponse<T> response;
        try {
            response = http.send(request, handler);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new MapiException(0, "INTERRUPTED", interrupted.getMessage());
            }
            throw new MapiException(0, "CONNECTION_FAILED", e.getMessage());
        }
        if (response.statusCode() >= 400) {
            Map<String, Object> body = parse(response.body().toString());
            Map<String, Object> error = body.get("error") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            throw new MapiException(response.statusCode(),
                    String.valueOf(error.getOrDefault("code", "UNKNOWN")),
                    String.valueOf(error.getOrDefault("message", "")));
        }
        return response.body();
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
    }

    private Map<String, Object> jsonGet(String path) {
        return parse(exchange(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString()));
    }

    private Map<String, Object> jsonPost(String path, Map<String, Object> body, String idempotencyKey) {
        HttpRequest.Builder builder = builder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(dev.example.mapi.internal.json.JsonWriter.write(body)));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return parse(exchange(builder.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private Map<String, Object> jsonDelete(String path) {
        return parse(exchange(builder(path).DELETE().build(), HttpResponse.BodyHandlers.ofString()));
    }

    /**
     * @return the parsed JSON object or an empty map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parse(String text) {
        try {
            Object parsed = dev.example.mapi.internal.json.JsonParser.parse(text);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (RuntimeException ignored) {
            // Handled as empty by the caller.
        }
        return new LinkedHashMap<>();
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** @return health payload */
    public Map<String, Object> health() {
        return jsonGet("/api/v1/health");
    }

    /** @return info payload (identity, scopes, support) */
    public Map<String, Object> info() {
        return jsonGet("/api/v1/info");
    }

    /** @return readiness payload */
    public Map<String, Object> ready() {
        return jsonGet("/api/v1/ready");
    }

    /** @return server status payload */
    public Map<String, Object> serverStatus() {
        return jsonGet("/api/v1/server/status");
    }

    /**
     * @param fields field projection or {@code null} for all
     * @param limit  page size
     * @param offset page offset
     * @return players payload
     */
    public Map<String, Object> players(List<String> fields, int limit, int offset) {
        StringBuilder path = new StringBuilder("/api/v1/server/players?limit=").append(limit)
                .append("&offset=").append(offset);
        if (fields != null && !fields.isEmpty()) {
            path.append("&fields=").append(String.join(",", fields));
        }
        return jsonGet(path.toString());
    }

    /**
     * @param dimension dimension id
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @return block payload
     */
    public Map<String, Object> block(String dimension, int x, int y, int z) {
        return jsonGet("/api/v1/server/world/block?dimension=" + urlEncode(dimension)
                + "&x=" + x + "&y=" + y + "&z=" + z);
    }

    /**
     * @param dimension dimension id
     * @return world time payload
     */
    public Map<String, Object> worldTime(String dimension) {
        return jsonGet("/api/v1/server/world/time?dimension=" + urlEncode(dimension));
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    /**
     * Creates a task (202 expected).
     *
     * @param kind                       task kind
     * @param payload                    kind payload
     * @param deadlineMs                 wall-time deadline or {@code null}
     * @param idempotencyKey             retry-safe key or {@code null}
     * @param expectedWorldSessionId     stale-session precondition or
     *                                   {@code null}
     * @return the task snapshot
     */
    public Map<String, Object> createTask(String kind, Map<String, Object> payload, Long deadlineMs,
            String idempotencyKey, String expectedWorldSessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("payload", payload == null ? Map.of() : payload);
        if (deadlineMs != null) {
            body.put("deadlineMs", deadlineMs);
        }
        if (expectedWorldSessionId != null) {
            body.put("expectedWorldSessionId", expectedWorldSessionId);
        }
        Map<String, Object> task = jsonPost("/api/v1/tasks", body, idempotencyKey);
        return task;
    }

    /**
     * @param taskId task id
     * @return the task snapshot
     */
    public Map<String, Object> task(String taskId) {
        return jsonGet("/api/v1/tasks/" + taskId);
    }

    /**
     * Polls until the task reaches a terminal state or the budget expires.
     *
     * @param taskId   task id
     * @param timeoutS total budget in seconds
     * @return the last seen snapshot
     * @throws InterruptedException on interrupt
     */
    public Map<String, Object> waitTask(String taskId, double timeoutS) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (long) (timeoutS * 1000);
        Map<String, Object> snapshot = task(taskId);
        while (!isTerminal(snapshot)) {
            if (System.currentTimeMillis() >= deadline) {
                return snapshot;
            }
            Thread.sleep(200);
            snapshot = task(taskId);
        }
        return snapshot;
    }

    private static boolean isTerminal(Map<String, Object> snapshot) {
        return snapshot.get("state") instanceof String state
                && Set.of("succeeded", "failed", "cancelled", "expired").contains(state);
    }

    /**
     * @param taskId task id
     * @return the post-cancellation snapshot
     */
    public Map<String, Object> cancelTask(String taskId) {
        return jsonDelete("/api/v1/tasks/" + taskId);
    }

    // ------------------------------------------------------------------
    // Leases / commands
    // ------------------------------------------------------------------

    /** @return all lease snapshots */
    public Map<String, Object> leases() {
        return jsonGet("/api/v1/leases");
    }

    /**
     * @param leaseType lease type (§5.3)
     * @param ttlMs time-to-live or {@code null} for the default
     * @param conflict reject/queue/preempt or {@code null} for reject
     * @return the lease snapshot
     */
    public Map<String, Object> acquireLease(String leaseType, Long ttlMs, String conflict) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lease", leaseType);
        if (ttlMs != null) {
            body.put("ttlMs", ttlMs);
        }
        if (conflict != null) {
            body.put("conflict", conflict);
        }
        return jsonPost("/api/v1/leases", body, null);
    }

    /**
     * @param leaseId lease id
     * @param ttlMs new time-to-live from now or {@code null}
     * @return the refreshed snapshot
     */
    public Map<String, Object> renewLease(String leaseId, Long ttlMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (ttlMs != null) {
            body.put("ttlMs", ttlMs);
        }
        return jsonPost("/api/v1/leases/" + leaseId + "/renew", body, null);
    }

    /**
     * @param leaseId lease id
     * @return the post-release snapshot
     */
    public Map<String, Object> releaseLease(String leaseId) {
        return jsonDelete("/api/v1/leases/" + leaseId);
    }

    /**
     * Executes a console command (commands.execute scope).
     *
     * @param command command text without leading slash
     * @param expectedWorldSessionId stale-session precondition or {@code null}
     * @return the execution outcome
     */
    public Map<String, Object> executeCommand(String command, String expectedWorldSessionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        if (expectedWorldSessionId != null) {
            body.put("expectedWorldSessionId", expectedWorldSessionId);
        }
        return jsonPost("/api/v1/server/commands/execute", body, null);
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    /**
     * Fetches one event page.
     *
     * @param after exclusive cursor
     * @return the events payload (events, headSeq, oldestSeq, gap?)
     */
    public Map<String, Object> events(long after) {
        return jsonGet("/api/v1/events?after=" + after);
    }

    /**
     * Fetches events after a cursor, advancing it; returns the page plus the
     * effective next cursor under key {@code nextCursor}.
     *
     * @param after exclusive cursor
     * @return page with {@code nextCursor} for resume
     */
    public Map<String, Object> eventsAfter(long after) {
        Map<String, Object> page = events(after);
        long next = after;
        if (page.get("events") instanceof List<?> events) {
            for (Object event : events) {
                if (event instanceof Map<?, ?> record && record.get("seq") instanceof Number seq) {
                    next = Math.max(next, seq.longValue());
                }
            }
        }
        if (page.get("headSeq") instanceof Number head) {
            next = Math.max(next, head.longValue());
        }
        page.put("nextCursor", next);
        return page;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
