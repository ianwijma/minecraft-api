package dev.example.mapi.internal;

/**
 * Internal abstraction over the loader's client lifecycle events. Loader
 * adapters translate their platform events into these calls:
 *
 * <pre>
 * client started   -&gt; onClientStarted()
 * client stopping  -&gt; onClientStopping()
 * </pre>
 *
 * <p>On clients the HTTP listener lives with the client process (available
 * at the main menu, surviving world exit); only {@code onClientStopping}
 * tears it down. Dedicated servers never fire these callbacks.
 */
public interface ClientLifecycleListener {

    /**
     * The game client has started (main menu reachable). Called on the
     * client thread after client initialization.
     */
    void onClientStarted();

    /**
     * The game client is shutting down. The HTTP listener must be stopped
     * here on clients.
     */
    void onClientStopping();
}
