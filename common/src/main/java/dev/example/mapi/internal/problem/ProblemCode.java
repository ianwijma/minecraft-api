package dev.example.mapi.internal.problem;

/**
 * Registry of every problem code the MAPI HTTP API can report. The wire name
 * is the enum name itself; {@link #wireCode()} exists for explicit mapping
 * and tests.
 *
 * <p>Two families:
 * <ul>
 *   <li>{@link Family#MIDDLEWARE} — transport-level rejections emitted before
 *       routing (auth, rate limiting, host checks). These codes predate the
 *       expanded scope and their wire behavior is frozen by contract tests.
 *   <li>{@link Family#OPERATION} — operation-level codes named by
 *       {@code docs/product-spec.md}; new operation endpoints must reuse
 *       these instead of inventing synonyms.
 * </ul>
 *
 * <p>New codes are added only together with the chunk that introduces the
 * capability, with contract tests covering status and body shape.
 */
public enum ProblemCode {

    INTERNAL("INTERNAL", 500, false, Family.MIDDLEWARE,
            "Unexpected server-side failure"),
    FORBIDDEN_HOST("FORBIDDEN_HOST", 403, false, Family.MIDDLEWARE,
            "Host header not allowed"),
    FORBIDDEN_ORIGIN("FORBIDDEN_ORIGIN", 403, false, Family.MIDDLEWARE,
            "Origin not allowed"),
    RATE_LIMITED("RATE_LIMITED", 429, true, Family.MIDDLEWARE,
            "Per-client rate limit exceeded"),
    UNAUTHORIZED("UNAUTHORIZED", 401, false, Family.MIDDLEWARE,
            "Missing or invalid bearer token"),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405, false, Family.MIDDLEWARE,
            "HTTP method not supported by this endpoint"),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE", 413, false, Family.MIDDLEWARE,
            "Request body exceeds the configured limit"),
    NOT_FOUND("NOT_FOUND", 404, false, Family.MIDDLEWARE,
            "Unknown endpoint"),
    SERVER_BUSY("SERVER_BUSY", 503, true, Family.MIDDLEWARE,
            "Game thread busy; bounded wait elapsed"),

    EXECUTION_MODE_UNSUPPORTED("EXECUTION_MODE_UNSUPPORTED", 422, false, Family.OPERATION,
            "The requested executionMode is not supported by this operation (spec §3.3)"),
    WORLD_NOT_LOADED("WORLD_NOT_LOADED", 409, false, Family.OPERATION,
            "No world is loaded for the requested world-scoped operation (spec §6)"),
    STALE_WORLD("STALE_WORLD", 409, false, Family.OPERATION,
            "The request carried a previous world identity (spec §6)"),
    WORLD_UNLOADED("WORLD_UNLOADED", 409, false, Family.OPERATION,
            "A world-scoped job was terminated because its world unloaded (spec §6)"),
    SERVER_PAUSED("SERVER_PAUSED", 409, true, Family.OPERATION,
            "Simulation progress is unavailable because the server is paused (spec §4.4)"),
    CLOCK_NOT_ADVANCING("CLOCK_NOT_ADVANCING", 409, true, Family.OPERATION,
            "The required clock is not advancing (spec §4.4)"),
    SNAPSHOT_EXPIRED("SNAPSHOT_EXPIRED", 410, false, Family.OPERATION,
            "A retained snapshot exceeded its retention period (spec §12)"),
    TOOLTIP_SEMANTICS_UNAVAILABLE("TOOLTIP_SEMANTICS_UNAVAILABLE", 422, false, Family.OPERATION,
            "Only a screenshot region is available; no semantic tooltip data (spec §10.2)"),

    INSUFFICIENT_SCOPE("INSUFFICIENT_SCOPE", 403, false, Family.OPERATION,
            "The caller lacks a required scope or grant (spec §14)"),
    DESTRUCTIVE_INTENT_REQUIRED("DESTRUCTIVE_INTENT_REQUIRED", 428, false, Family.OPERATION,
            "A destructive request needs explicit caller intent, not just the grant (spec §14)"),
    DEADLINE_EXCEEDED("DEADLINE_EXCEEDED", 504, true, Family.OPERATION,
            "Wall-clock deadline elapsed before completion (spec §4.3)");

    /** Error-code family, used for tooling and documentation. */
    public enum Family {
        /** Transport-level rejections emitted before routing. */
        MIDDLEWARE,
        /** Operation-level codes named by the product specification. */
        OPERATION
    }

    private final String wireCode;
    private final int httpStatus;
    private final boolean retryable;
    private final Family family;
    private final String description;

    ProblemCode(String wireCode, int httpStatus, boolean retryable, Family family, String description) {
        this.wireCode = wireCode;
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.family = family;
        this.description = description;
    }

    /** @return the exact string emitted as {@code error.code} in responses */
    public String wireCode() {
        return wireCode;
    }

    /** @return the HTTP status this problem is reported with */
    public int httpStatus() {
        return httpStatus;
    }

    /**
     * @return true when a retry with unchanged inputs can plausibly succeed
     *     (for example after a client-side backoff or an operator recovery
     *     action)
     */
    public boolean retryable() {
        return retryable;
    }

    /** @return the family this code belongs to */
    public Family family() {
        return family;
    }

    /** @return human-readable description, never {@code null} */
    public String description() {
        return description;
    }
}
