package dev.example.mapi.internal.operation;

/**
 * Execution modes from {@code docs/product-spec.md} §3.1. Every action
 * operation declares which modes it supports; the caller selects one and the
 * actual mode is always reported — there is no silent fallback.
 */
public enum ExecutionMode {

    /** Synthetic key, character, mouse, or scroll events through the supported game input path. */
    RAW_INPUT("raw-input"),
    /** Ordinary client interaction logic without reproducing every underlying input event. */
    CLIENT_LOGIC("client-logic"),
    /** Direct development/setup operation bypassing the ordinary player interaction path. */
    PRIVILEGED("privileged");

    private final String wireName;

    ExecutionMode(String wireName) {
        this.wireName = wireName;
    }

    /** @return the exact string used on the wire, never {@code null} */
    public String wireName() {
        return wireName;
    }
}
