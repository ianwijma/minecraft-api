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
}
