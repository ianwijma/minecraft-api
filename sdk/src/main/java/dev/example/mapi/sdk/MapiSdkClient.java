package dev.example.mapi.sdk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Java client for the MAPI local HTTP API (spec deliverable 2). Zero
 * dependencies, pure JDK. Implements the documented wire semantics: bearer
 * auth, problem-code error envelopes, loopback-only usage.
 *
 * <p>One method per spec operationId, plus a generic request core for
 * operations added after SDK generation.
 */
public final class MapiSdkClient {

    /** One HTTP outcome. */
    public record Result(int status, Map<String, Object> body, boolean ok) {

        /** @return the error code when the body carries an error envelope */
        public Optional<String> errorCode() {
            if (body.get("error") instanceof Map<?, ?> error && error.get("code") != null) {
                return Optional.of(String.valueOf(error.get("code")));
            }
            return Optional.empty();
        }
    }

    /** Thrown when the API returns a problem-code envelope. */
    public static final class MapiSdkException extends RuntimeException {

        private final int status;
        private final String code;

        public MapiSdkException(int status, String code, String message) {
            super("HTTP " + status + " " + code + ": " + message);
            this.status = status;
            this.code = code;
        }

        /** @return the HTTP status code */
        public int getStatus() { return status; }

        /** @return the problem-code wire name */
        public String getCode() { return code; }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final String token;
    private final Duration timeout;

    /**
     * @param base  base URL (for example {@code http://127.0.0.1:25586})
     * @param token bearer token, never blank
     */
    public MapiSdkClient(String base, String token) {
        this(base, token, Duration.ofSeconds(10));
    }

    /**
     * @param base    base URL
     * @param token   bearer token
     * @param timeout per-request timeout
     */
    public MapiSdkClient(String base, String token, Duration timeout) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("a bearer token is required");
        }
        this.base = base.replaceAll("/+$", "");
        this.token = token;
        this.timeout = timeout;
    }

    // -- generic core --------------------------------------------------------

    /**
     * Performs a GET.
     *
     * @param path path under the base URL (starting with {@code /})
     * @return the outcome
     * @throws MapiSdkException on a problem-code envelope
     */
    public Result get(String path) {
        return send(requestBuilder(path).GET().build());
    }

    /**
     * Performs a POST with a JSON body.
     *
     * @param path path under the base URL
     * @param body JSON body (may be an empty map)
     * @return the outcome
     * @throws MapiSdkException on a problem-code envelope
     */
    public Result post(String path, Map<String, Object> body) {
        String json = SdkJson.write(body == null ? Map.of() : body);
        return send(requestBuilder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token);
    }

    private Result send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            Map<String, Object> body = SdkJson.asObject(response.body());
            int status = response.statusCode();
            boolean ok = status >= 200 && status < 300;
            if (!ok) {
                if (body.get("error") instanceof Map<?, ?> errorMap
                        && errorMap.get("code") != null) {
                    throw new MapiSdkException(status,
                            String.valueOf(errorMap.get("code")),
                            String.valueOf(errorMap.get("message")));
                }
            }
            return new Result(status, body, ok);
        } catch (MapiSdkException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("connection to " + base + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + base, e);
        }
    }

    // -- convenience operations (spec §6/§4.4) -------------------------------

    /**
     * Waits until the world session phase is ACTIVE (spec §6, §4.4: bounded
     * by a wall-clock deadline).
     *
     * @param timeoutMs maximum wait in milliseconds
     * @param pollMs    interval between polls in milliseconds
     * @return true when the phase reaches ACTIVE within the deadline
     */
    public boolean awaitWorldActive(long timeoutMs, int pollMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var world = get("/api/v1/server/world");
            if (world.ok() && "ACTIVE".equals(world.body().get("phase"))) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
