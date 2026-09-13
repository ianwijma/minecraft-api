package dev.example.mapi.internal;

import org.slf4j.Logger;

/**
 * Internal abstraction over the loader's server lifecycle events. Loader
 * adapters translate their platform events into these calls:
 *
 * <pre>
 * server starting  -&gt; onServerStarting(ServerHandle)
 * server stopping  -&gt; onServerStopping()
 * server stopped   -&gt; onServerStopped()
 * </pre>
 *
 * <p>{@code onServerStopping} is where HTTP and services shut down, so it
 * must fire on every stop path.
 */
public interface ServerLifecycleListener {

    /**
     * A server is starting (world load in progress). Called on the server
     * thread.
     *
     * @param handle handle to the starting server, never {@code null}
     */
    void onServerStarting(ServerHandle handle);

    /**
     * The server is stopping. Called before the server thread is torn down.
     */
    void onServerStopping();

    /**
     * The server has fully stopped; the handle must be considered invalid.
     */
    void onServerStopped();
}
