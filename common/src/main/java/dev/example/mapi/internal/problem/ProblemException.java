package dev.example.mapi.internal.problem;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Unchecked exception carrying a {@link ProblemCode}. Operation handlers
 * throw this to produce a correctly shaped HTTP error response; the HTTP
 * layer maps it via {@link ProblemJson}.
 */
public final class ProblemException extends RuntimeException {

    private final ProblemCode code;
    private final Map<String, Object> details;

    /**
     * @param code the problem code, never {@code null}
     */
    public ProblemException(ProblemCode code) {
        this(code, code.description(), null);
    }

    /**
     * @param code    the problem code, never {@code null}
     * @param message human-readable detail, never {@code null}
     */
    public ProblemException(ProblemCode code, String message) {
        this(code, message, null);
    }

    /**
     * @param code    the problem code, never {@code null}
     * @param message human-readable detail, never {@code null}
     * @param details machine-readable structured detail; may be {@code null}
     */
    public ProblemException(ProblemCode code, String message, Map<String, Object> details) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
        this.details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(details);
    }

    /** @return the problem code, never {@code null} */
    public ProblemCode code() {
        return code;
    }

    /** @return unmodifiable structured detail, possibly empty, never {@code null} */
    public Map<String, Object> details() {
        return details;
    }
}
