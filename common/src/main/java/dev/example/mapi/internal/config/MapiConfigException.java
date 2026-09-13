package dev.example.mapi.internal.config;

/**
 * Thrown when the MAPI configuration is invalid or unsafe (for example, HTTP
 * enabled without a usable bearer token). The message always includes
 * remediation steps and never contains secret values.
 */
public class MapiConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable problem description with remediation; must
     *                not contain secret values
     */
    public MapiConfigException(String message) {
        super(message);
    }
}
