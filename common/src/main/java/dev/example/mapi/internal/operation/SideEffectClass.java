package dev.example.mapi.internal.operation;

/**
 * Declared side-effect class of an operation (spec §14). This is a disclosure
 * for callers and tooling, not a safety guarantee: raw UI control can cause
 * ordinary gameplay destruction regardless of the declared class.
 */
public enum SideEffectClass {

    /** No observable state change. */
    READ_ONLY("read-only"),
    /** Changes client-local state only (for example synthetic input state). */
    LOCAL("local"),
    /** Changes game or world state within the operation's documented scope. */
    GAME("game"),
    /** Unknown or arbitrary side effects; administrative access. */
    UNRESTRICTED("unrestricted");

    private final String wireName;

    SideEffectClass(String wireName) {
        this.wireName = wireName;
    }

    /** @return the exact string used on the wire, never {@code null} */
    public String wireName() {
        return wireName;
    }
}
