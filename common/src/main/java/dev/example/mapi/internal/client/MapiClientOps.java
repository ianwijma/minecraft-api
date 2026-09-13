package dev.example.mapi.internal.client;

/**
 * Loader-neutral client operations seam (slice 0.6). Implemented once per
 * loader in client-only code that dedicated servers never load (Fabric
 * client source set / NeoForge {@code @OnlyIn(Dist.CLIENT)} classes under
 * {@code dev.example.mapi.client.*}); the HTTP server reaches it only via
 * {@code MapiBootstrap.registerClientOps}.
 *
 * <p>Threading contract: implementations must schedule every read onto the
 * client thread themselves (via the scheduler handed to
 * {@code MapiBootstrap.registerClientOps}) and never return live game
 * objects — only immutable snapshots.
 */
public interface MapiClientOps {

    /**
     * @return a bounded client-thread read of the local client status
     */
    ClientStatusSnapshot status();

    /**
     * Walks the open screen's widget tree (best-effort). An empty root node
     * ({@code currentScreen == null}) is reported when the HUD is showing.
     *
     * @return the tree root; {@code null} is never returned — use a node
     *         with {@code widgetClass == null} for "no screen"
     */
    ScreenNode screenTree();

    /**
     * Input-mode key action (spec §5.2): drives the game's own key-mapping
     * input path (the same mechanism the physical keyboard funnels into).
     *
     * @param mapping mapping name as reported by the game (e.g.
     *                {@code key.forward}); only mappings the game reports
     *                are accepted
     * @param action  {@code press}, {@code release}, or {@code tap}
     * @return the action result with the authoritative key state
     * @throws UnknownMappingException when the mapping name is not reported
     *                                 by this client
     * @throws IllegalArgumentException when the action is unknown
     */
    KeyActionResult pressKey(String mapping, String action);

    /**
     * Captures the main framebuffer as PNG into the instance's
     * {@code mcapi/screenshots} directory (controlled path).
     *
     * @param frameId monotonic capture identifier assigned by the caller
     * @return the capture metadata
     */
    ScreenshotResult captureScreenshot(long frameId);

    /** The requested mapping does not exist on this client. */
    class UnknownMappingException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /**
         * @param message description including the requested mapping
         */
        public UnknownMappingException(String message) {
            super(message);
        }
    }
}
