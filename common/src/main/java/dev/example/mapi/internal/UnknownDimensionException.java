package dev.example.mapi.internal;

/**
 * Thrown by {@link ServerHandle} read suppliers when a dimension id is not
 * known to the running server. Never carries secret data.
 */
public class UnknownDimensionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description including the offending dimension id
     */
    public UnknownDimensionException(String message) {
        super(message);
    }
}
