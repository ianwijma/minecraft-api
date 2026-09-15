package dev.example.mapi.fabric;

import dev.example.mapi.internal.ClientLifecycleListener;
import java.util.function.Consumer;

/**
 * Main-source-set seam for client-installed hooks. The client entrypoint
 * installs registrars here so the platform adapter can forward lifecycle
 * registrations without referencing client-only event classes
 * (loom.splitEnvironmentSourceSets).
 */
public final class MapiFabricHooks {

    private static volatile Consumer<ClientLifecycleListener> clientLifecycleRegistrar =
            listener -> { };
    private static volatile java.util.function.BooleanSupplier clientShutdownSupplier =
            () -> false;

    private MapiFabricHooks() {
    }

    /**
     * Installs the client lifecycle registrar (client source set only).
     *
     * @param registrar consumer receiving the runtime's client listener
     */
    public static void setClientLifecycleRegistrar(Consumer<ClientLifecycleListener> registrar) {
        clientLifecycleRegistrar = java.util.Objects.requireNonNull(registrar, "registrar");
    }

    /**
     * @param listener the runtime's client lifecycle listener
     */
    static void registerClientLifecycle(ClientLifecycleListener listener) {
        clientLifecycleRegistrar.accept(listener);
    }

    /**
     * Installs the client shutdown supplier (client source set only).
     *
     * @param supplier invoked when a shutdown is requested with no server;
     *                 true when the client stop was scheduled
     */
    public static void setClientShutdownSupplier(java.util.function.BooleanSupplier supplier) {
        clientShutdownSupplier = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    /**
     * @param hasServer true when an integrated server is running (server halt
     *                  path handled by the platform)
     * @return true when the client stop was scheduled
     */
    static boolean runClientShutdown(boolean hasServer) {
        return !hasServer && clientShutdownSupplier.getAsBoolean();
    }
}
