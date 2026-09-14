package dev.example.mapi.internal.client;

/**
 * Registration point for loader client bridges. Client-only entrypoints call
 * {@link #set(ClientBridge)} during client initialization; the platform
 * default {@code clientBridge()} reads from here. Main source sets never
 * reference client classes — the bridge instance itself lives in client-only
 * code (ADR-0002).
 */
public final class ClientBridgeHolder {

    private static volatile ClientBridge registered = ClientBridge.NONE;

    private ClientBridgeHolder() {
    }

    /** @return the registered bridge, or {@link ClientBridge#NONE} on dedicated servers */
    public static ClientBridge get() {
        return registered;
    }

    /**
     * Registers the bridge (called from client-only entrypoints only).
     *
     * @param bridge the bridge, never {@code null}
     */
    public static void set(ClientBridge bridge) {
        registered = java.util.Objects.requireNonNull(bridge, "bridge");
    }
}
