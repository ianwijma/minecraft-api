package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import dev.example.mapi.internal.json.JsonReader;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP request parsing and bounded query/body conversion. */
final class HttpApiRequest {

    private static final String BEARER_PREFIX = "Bearer ";

    private HttpApiRequest() {}

    static Map<String, Object> readJsonObject(HttpExchange exchange, String path) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(HttpApiServer.MAX_BODY_BYTES + 1);
            if (bytes.length > HttpApiServer.MAX_BODY_BYTES) {
                throw new ProblemException(ProblemCode.PAYLOAD_TOO_LARGE,
                        "Request body exceeds " + HttpApiServer.MAX_BODY_BYTES + " bytes");
            }
            if (bytes.length == 0) {
                return Map.of();
            }
            try {
                Object parsed = JsonReader.parse(bytes);
                if (!(parsed instanceof Map<?, ?> map)) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST,
                            "request body must be a JSON object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return result;
            } catch (IllegalArgumentException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "invalid JSON body: " + e.getMessage());
            }
        }
    }

    static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null
                || authorizationHeader.length() <= BEARER_PREFIX.length()
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return authorizationHeader.substring(BEARER_PREFIX.length()).trim();
    }

    static String stringField(Map<String, Object> body, String key) {
        return body.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    static long longField(Map<String, Object> body, String key, long fallback) {
        return body.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    static int intParam(HttpExchange exchange, String name, int fallback) {
        String raw = queryParams(exchange.getRequestURI().getRawQuery()).get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, name + " must be a positive integer");
        }
    }

    static boolean declaredBodyTooLarge(HttpExchange exchange) {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength == null) {
            return false;
        }
        long value;
        try {
            value = Long.parseLong(contentLength.trim());
        } catch (NumberFormatException e) {
            return true;
        }
        return value > HttpApiServer.MAX_BODY_BYTES;
    }
}
