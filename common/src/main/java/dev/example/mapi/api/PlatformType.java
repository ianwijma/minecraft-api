package dev.example.mapi.api;

/**
 * The mod loader MAPI is running on.
 *
 * <p>This type is loader-neutral: it is safe for consumers to switch over it
 * without introducing a compile-time dependency on Fabric or NeoForge.
 */
public enum PlatformType {

    /** Fabric (fabric-loader). */
    FABRIC("fabric"),

    /** NeoForge. */
    NEOFORGE("neoforge");

    private final String id;

    PlatformType(String id) {
        this.id = id;
    }

    /**
     * Stable lowercase identifier, also used as the {@code platform} value in
     * the local HTTP API responses.
     *
     * @return the platform identifier, never {@code null} or empty
     */
    public String id() {
        return id;
    }
}
