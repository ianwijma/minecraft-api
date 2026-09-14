package dev.example.mapi.internal.client;

/**
 * Result of a client screenshot capture. {@code frameId} is a monotonic
 * per-process identifier of the captured frame instance (Phase 0 semantics;
 * correlation with rendered frames comes with render-lifecycle work).
 *
 * @param frameId assigned capture identifier
 * @param path    file path relative to the instance game directory
 * @param width   image width in pixels
 * @param height  image height in pixels
 * @param bytes   encoded PNG size in bytes
 */
public record ScreenshotResult(long frameId, String path, int width, int height, int bytes) {
}
