package dev.example.mapi.internal;

/**
 * Raw, loader-supplied mod metadata snapshot (process-wide, thread-safe to
 * read at any time).
 */
public record RawModInfo(String id, String name, String version) {
}
