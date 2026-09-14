package dev.example.mapi.internal;

/**
 * Physical side of the running JVM process: a client process (which may host
 * an integrated server) or a dedicated server process. This is a property of
 * the process, not of the current game session.
 */
public enum PhysicalSide {

    /** A physical client process (local client; integrated server optional). */
    CLIENT("client"),

    /** A dedicated server process (no client code loaded). */
    DEDICATED_SERVER("dedicatedServer");

    private final String id;

    PhysicalSide(String id) {
        this.id = id;
    }

    /**
     * @return the stable lowercase identifier used in HTTP payloads and the
     *         discovery file, never {@code null}
     */
    public String id() {
        return id;
    }
}
