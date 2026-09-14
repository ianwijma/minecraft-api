package dev.example.mapi.internal.client;

import java.util.List;

/**
 * Immutable snapshot of the local client, captured on the client thread.
 * This is the first client-side observation op (slice 0.6); screenshots and
 * input follow once render-thread capture is verified in game.
 *
 * @param windowWidth      framebuffer width in pixels
 * @param windowHeight     framebuffer height in pixels
 * @param guiScaledWidth   GUI-space width
 * @param guiScaledHeight  GUI-space height
 * @param guiScale         active GUI scale factor
 * @param currentScreenClass simple class name of the open screen or
 *                         {@code null} when the game HUD is showing
 * @param playerPresent    true when a local player exists (in a world)
 * @param dimension        current dimension id or {@code null}
 * @param gameTime         level game time or {@code null}
 */
public record ClientStatusSnapshot(
        int windowWidth,
        int windowHeight,
        int guiScaledWidth,
        int guiScaledHeight,
        int guiScale,
        String currentScreenClass,
        boolean playerPresent,
        String dimension,
        Long gameTime) {
}
