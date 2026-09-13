package dev.example.mapi.internal.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.json.JsonWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProblemCodeTest {

    @Test
    void operationCodesMatchSpecNames() {
        assertEquals(422, ProblemCode.EXECUTION_MODE_UNSUPPORTED.httpStatus());
        assertEquals(409, ProblemCode.WORLD_NOT_LOADED.httpStatus());
        assertEquals(409, ProblemCode.STALE_WORLD.httpStatus());
        assertEquals(409, ProblemCode.WORLD_UNLOADED.httpStatus());
        assertEquals(409, ProblemCode.SERVER_PAUSED.httpStatus());
        assertEquals(409, ProblemCode.CLOCK_NOT_ADVANCING.httpStatus());
        assertEquals(410, ProblemCode.SNAPSHOT_EXPIRED.httpStatus());
        assertEquals(422, ProblemCode.TOOLTIP_SEMANTICS_UNAVAILABLE.httpStatus());
        for (ProblemCode code : ProblemCode.values()) {
            if (code.family() == ProblemCode.Family.OPERATION) {
                assertTrue(code.retryable() == (code == ProblemCode.SERVER_PAUSED
                        || code == ProblemCode.CLOCK_NOT_ADVANCING),
                        "unexpected retryability for " + code);
            }
        }
    }

    @Test
    void authorizationCodesUseDefinedStatuses() {
        assertEquals(403, ProblemCode.INSUFFICIENT_SCOPE.httpStatus());
        assertEquals(428, ProblemCode.DESTRUCTIVE_INTENT_REQUIRED.httpStatus());
    }

    @Test
    void middlewareCodesPreserveLegacyStatuses() {
        assertEquals(500, ProblemCode.INTERNAL.httpStatus());
        assertEquals(403, ProblemCode.FORBIDDEN_HOST.httpStatus());
        assertEquals(403, ProblemCode.FORBIDDEN_ORIGIN.httpStatus());
        assertEquals(429, ProblemCode.RATE_LIMITED.httpStatus());
        assertEquals(401, ProblemCode.UNAUTHORIZED.httpStatus());
        assertEquals(405, ProblemCode.METHOD_NOT_ALLOWED.httpStatus());
        assertEquals(413, ProblemCode.PAYLOAD_TOO_LARGE.httpStatus());
        assertEquals(404, ProblemCode.NOT_FOUND.httpStatus());
        assertEquals(503, ProblemCode.SERVER_BUSY.httpStatus());
    }

    @Test
    void wireCodesAreUniqueUpperSnakeAndMatchEnumNames() {
        var seen = new java.util.HashSet<String>();
        for (ProblemCode code : ProblemCode.values()) {
            assertTrue(seen.add(code.wireCode()), "duplicate wire code " + code.wireCode());
            assertEquals(code.name(), code.wireCode());
            assertTrue(code.wireCode().matches("[A-Z][A-Z0-9_]*"), code.wireCode());
        }
    }

    @Test
    void errorBodyShapeWithoutDetailsIsStable() {
        Map<String, Object> body = ProblemJson.errorBody(ProblemCode.NOT_FOUND, "Unknown endpoint: /x", null, 1);
        assertEquals("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Unknown endpoint: /x\"},"
                + "\"protocolVersion\":1}", JsonWriter.write(body));
    }

    @Test
    void errorBodyIncludesDetailsOnlyWhenPresent() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestedMode", "raw-input");
        details.put("supported", java.util.List.of("client-logic"));
        Map<String, Object> body = ProblemJson.errorBody(
                ProblemCode.EXECUTION_MODE_UNSUPPORTED, "Mode not supported", details, 1);
        assertEquals("{\"error\":{\"code\":\"EXECUTION_MODE_UNSUPPORTED\","
                + "\"message\":\"Mode not supported\","
                + "\"details\":{\"requestedMode\":\"raw-input\",\"supported\":[\"client-logic\"]}},"
                + "\"protocolVersion\":1}", JsonWriter.write(body));

        Map<String, Object> empty = ProblemJson.errorBody(
                ProblemCode.EXECUTION_MODE_UNSUPPORTED, "Mode not supported", Map.of(), 1);
        assertTrue(JsonWriter.write(empty).contains("\"details\"") == false);
    }

    @Test
    void problemExceptionNormalizesAndGuardsArguments() {
        ProblemException e = new ProblemException(ProblemCode.WORLD_NOT_LOADED, "no world");
        assertEquals(ProblemCode.WORLD_NOT_LOADED, e.code());
        assertTrue(e.details().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> e.details().put("x", "y"));

        assertThrows(NullPointerException.class, () -> new ProblemException(null, "m"));
        assertThrows(NullPointerException.class, () -> new ProblemException(ProblemCode.INTERNAL, null));

        ProblemException withDetails = new ProblemException(
                ProblemCode.STALE_WORLD, "stale", Map.of("presented", "w-1", "current", "w-2"));
        assertEquals("w-1", withDetails.details().get("presented"));
    }
}
