package dev.example.mapi.internal;

import java.util.function.Supplier;

/**
 * Handle to an active Minecraft server (dedicated or integrated), created by
 * the loader adapter when the server starts and invalidated when it stops.
 *
 * <p>Methods must be safe to call from any thread; adapters are responsible
 * for the thread affinity of the underlying game objects.
 */
public interface ServerHandle {

    /**
     * @return epoch milliseconds at which this server instance started
     */
    long startedAtEpochMs();

    /**
     * Schedules the task to run on the Minecraft server thread. If called
     * from the server thread, implementations may run the task inline.
     *
     * @param task the task to run, never {@code null}
     */
    void executeOnServerThread(Runnable task);

    /**
     * Reads a raw server status snapshot. Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}; it may touch live game state.
     *
     * @return the raw snapshot, never {@code null}
     */
    Supplier<RawServerInfo> infoSupplier();
}
