package dev.example.mapi.runner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thin HTTP client for the MAPI local API (public contract only, spec §13.1
 * style): bearer header, JSON envelope, explicit error mapping. No retries —
 * the runner layers its own policy on top and never silently abandons
 * outcomes (spec §18).
 */
final class MapiClient {

    /** One HTTP outcome. */
    record Result(int status, Map<String, Object> body, String rawBody, boolean ok) {

        /** @return the error code when the body carries an error envelope */
        public Optional<String> errorCode() {
            if (body.get("error") instanceof Map<?, ?> error && error.get("code") != null) {
                return Optional.of(String.valueOf(error.get("code")));
            }
            return Optional.empty();
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String base;
    private final String token;

    /**
     * @param base  base URL (for example {@code http://127.0.0.1:25586})
     * @param token bearer token, never blank
     */
    MapiClient(String base, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("a bearer token is required");
        }
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    /**
     * Performs a GET.
     *
     * @param path path under the base URL (starting with {@code /})
     * @return the outcome
     */
    Result get(String path) {
        return send(requestBuilder(path).GET().build());
    }

    /**
     * Performs a POST with a JSON body.
     *
     * @param path path under the base URL
     * @param json request body ({@code null} for an empty object)
     * @return the outcome
     */
    Result post(String path, String json) {
        String body = json == null ? "{}" : json;
        return send(requestBuilder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private HttpRequest.Builder requestBuilder(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token);
    }

    private Result send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new Result(response.statusCode(), parseBody(response.body()),
                    response.body(),
                    response.statusCode() >= 200 && response.statusCode() < 300);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("connection to " + base + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + base, e);
        }
    }

    private static Map<String, Object> parseBody(String body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        Object parsed = MiniJson.parse(body);
        if (parsed instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
