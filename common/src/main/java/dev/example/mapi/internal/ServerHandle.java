package dev.example.mapi.internal;

import java.util.List;
import java.util.function.Supplier;

/**
 * Handle to an active Minecraft server (dedicated or integrated), created by
 * the loader adapter when the server starts and invalidated when it stops.
 *
 * <p>Methods must be safe to call from any thread; adapters are responsible
 * for the thread affinity of the underlying game objects. The suppliers read
 * live game state and must only be invoked via
 * {@link #executeOnServerThread(Runnable)} (see {@code MapiRuntime.tryReadOnServerThread}).
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

    /**
     * Reads the connected players. Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}.
     *
     * @return the player snapshots (order is unspecified), never {@code null}
     */
    Supplier<List<RawPlayerSnapshot>> playersSupplier();

    /**
     * Reads a block state. Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}.
     *
     * @param dimension dimension id ({@code namespace:path})
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @return the block read, or {@code null} when the containing chunk is
     *         not loaded (loaded-only policy)
     * @throws UnknownDimensionException when the dimension id is unknown to
     *                                   this server
     */
    Supplier<RawBlockRead> blockSupplier(String dimension, int x, int y, int z);

    /**
     * Reads world clocks for a dimension. Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}.
     *
     * @param dimension dimension id ({@code namespace:path})
     * @return the time read, never {@code null}
     * @throws UnknownDimensionException when the dimension id is unknown to
     *                                   this server
     */
    Supplier<RawWorldTime> timeSupplier(String dimension);

    /**
     * Reads the world data version. Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}.
     *
     * @return the data version stamp of the loaded world
     */
    Supplier<Integer> dataVersionSupplier();
}
