package dev.example.mapi.internal.world;

/** Lifecycle phase of the loaded server world session (spec §6). */
public enum WorldPhase {
    /** No world session exists. */
    NONE,
    /** A world session is starting; world-scoped work is not yet available. */
    LOADING,
    /** The world session is active. */
    ACTIVE,
    /** The world session is unloading; outstanding work is being terminated. */
    UNLOADING
}
