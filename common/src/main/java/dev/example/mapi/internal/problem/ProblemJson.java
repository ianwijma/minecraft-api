package dev.example.mapi.internal.problem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the JSON error-body shape used by the HTTP API:
 * {@code {"error":{"code":...,"message":...[,"details":...]},"protocolVersion":N}}.
 * The {@code details} key is omitted when empty, preserving the historical
 * middleware wire format byte-for-byte.
 */
public final class ProblemJson {

    private ProblemJson() {
    }

    /**
     * @param code            the problem code, never {@code null}
     * @param message         human-readable detail, never {@code null}
     * @param details         optional structured detail; {@code null} or empty
     *                        omits the key
     * @param protocolVersion protocol version to echo in the body
     * @return the error body as an ordered map ready for {@code JsonWriter}
     */
    public static Map<String, Object> errorBody(
            ProblemCode code, String message, Map<String, Object> details, int protocolVersion) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.wireCode());
        error.put("message", message);
        if (details != null && !details.isEmpty()) {
            error.put("details", details);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("protocolVersion", protocolVersion);
        return body;
    }
}
