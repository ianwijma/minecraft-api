package dev.example.mapi.internal;

/**
 * Thrown by {@link ServerHandle} registry suppliers when a registry type is
 * not supported. The message names the supported types.
 */
public class UnknownRegistryTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message description including the offending type
     */
    public UnknownRegistryTypeException(String message) {
        super(message);
    }
}
