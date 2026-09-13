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

    /**
     * Executes a command as the console with a permission ceiling (spec
     * §4.2: authorize the effect; §6.2: execute with ceiling). Callers must
     * only invoke this via {@link #executeOnServerThread(Runnable)}.
     *
     * @param command         the command text (without leading slash)
     * @param permissionLevel maximum permission level the source may use
     *                        (0..4)
     * @return the captured outcome, never {@code null}
     */
    Supplier<RawCommandResult> commandSupplier(String command, int permissionLevel);

    /**
     * Lists registry entry ids (sorted) for one page (spec §6.1 registry
     * inspection). Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}.
     *
     * @param type  registry type (block, item, entity_type, block_entity_type,
     *              fluid, sound_event, mob_effect, attribute)
     * @param limit page size (already clamped)
     * @param offset page offset (already clamped)
     * @return sorted id page plus total count, never {@code null}
     * @throws UnknownRegistryTypeException when the type is not supported
     */
    Supplier<RegistryIdPage> registryIdsSupplier(String type, int limit, int offset);

    /**
     * Lists tag ids for a registry type (sorted). Callers must only invoke
     * this via {@link #executeOnServerThread(Runnable)}.
     *
     * @param type registry type (block, item, entity_type, fluid)
     * @return sorted tag id list, never {@code null}
     * @throws UnknownRegistryTypeException when the type is not supported
     */
    Supplier<List<String>> tagIdsSupplier(String type);

    /**
     * Lists the member ids of one tag. Callers must only invoke this via
     * {@link #executeOnServerThread(Runnable)}.
     *
     * @param type registry type
     * @param tagId tag id ({@code namespace:path}, without the {@code #})
     * @return sorted member id list (empty when the tag is unknown)
     * @throws UnknownRegistryTypeException when the type is not supported
     */
    Supplier<List<String>> tagMembersSupplier(String type, String tagId);

    /**
     * Registry id page.
     *
     * @param ids   sorted page of ids
     * @param total total entries in the registry
     */
    record RegistryIdPage(List<String> ids, int total) {
    }

    /**
     * Reads a block entity under the loaded-only chunk policy. Callers must
     * only invoke this via {@link #executeOnServerThread(Runnable)}.
     *
     * @param dimension dimension id
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @return the read outcome (never {@code null}); the entity is
     *         {@code null} when absent
     * @throws UnknownDimensionException when the dimension id is unknown
     */
    Supplier<RawBlockEntityRead> blockEntitySupplier(String dimension, int x, int y, int z);

    /**
     * Reads the contents of a container block entity (chest, furnace, …)
     * under the loaded-only policy (spec §6.2 storage read). Callers must
     * only invoke this via {@link #executeOnServerThread(Runnable)}.
     *
     * @param dimension dimension id
     * @param x         block x
     * @param y         block y
     * @param z         block z
     * @return the read outcome; {@code entity()} is {@code null} when the
     *         block entity is absent or not a container
     * @throws UnknownDimensionException when the dimension id is unknown
     */
    Supplier<RawStorageRead> storageSupplier(String dimension, int x, int y, int z);
}
