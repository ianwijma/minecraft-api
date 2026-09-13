package dev.example.mapi.internal.client;

/**
 * Result of a client key action (input mode, spec §5.2).
 *
 * @param mapping the mapping name as reported by the game (e.g.
 *                {@code key.forward})
 * @param action  the executed action ({@code press}, {@code release},
 *                {@code tap})
 * @param isDown  authoritative key state right after the action; absent for
 *                {@code tap} (event-style action without a held state)
 */
public record KeyActionResult(String mapping, String action, Boolean isDown) {
}
