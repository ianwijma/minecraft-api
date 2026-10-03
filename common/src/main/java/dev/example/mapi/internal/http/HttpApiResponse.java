package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Shared HTTP response encoding for route handlers and middleware. */
final class HttpApiResponse {

    private HttpApiResponse() {}

    static void respond(HttpExchange exchange, int status, String json) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-MAPI-Protocol-Version",
                String.valueOf(HttpApiServer.PROTOCOL_VERSION));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static void error(HttpExchange exchange, ProblemCode code, String message) throws IOException {
        error(exchange, code, message, null);
    }

    static void error(HttpExchange exchange, ProblemCode code, String message,
            Map<String, Object> details) throws IOException {
        respond(exchange, code.httpStatus(), JsonWriter.write(
                ProblemJson.errorBody(code, message, details, HttpApiServer.PROTOCOL_VERSION)));
    }
}
