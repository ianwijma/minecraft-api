package dev.example.mapi.internal.operation;

/**
 * Security scopes from {@code docs/product-spec.md} §14. Scope grants are
 * config-backed (wired to configuration in plan chunk 4.2); until then the
 * configured bearer token grants the full set, preserving current behavior.
 */
public enum Scope {

    /** Direct connection setup to a target server. */
    CLIENT_CONNECT("client:connect"),
    /** Reading and changing client settings. */
    CLIENT_SETTINGS("client:settings"),
    /** Tick control (freeze, step, sprint, rate). */
    SERVER_TICK_CONTROL("server:tick-control"),
    /** Publishing an integrated server to LAN. */
    SERVER_PUBLISH("server:publish"),
    /** Permission to call destructive operations (still needs explicit intent). */
    OPERATIONS_DESTRUCTIVE("operations:destructive"),
    /** Permission to call unrestricted/administrative operations. */
    OPERATIONS_UNRESTRICTED("operations:unrestricted");

    private final String wireName;

    Scope(String wireName) {
        this.wireName = wireName;
    }

    /** @return the exact string used on the wire, never {@code null} */
    public String wireName() {
        return wireName;
    }
}
