package dev.example.mapi.fabric;

import dev.example.mapi.internal.ClientLifecycleListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Main-source-set seam for client-installed hooks. The client entrypoint
 * installs registrars here so the platform adapter can forward lifecycle
 * registrations without referencing client-only event classes
 * (loom.splitEnvironmentSourceSets).
 *
 * Ordering constraint: Fabric runs {@code main} entrypoints before
 * {@code client} entrypoints, and the runtime registers its listener from
 * the main entrypoint. Registrations therefore queue up in
 * {@link #pendingClientListeners} and are (re)played when the client
 * entrypoint installs the real registrar.
 */
public final class MapiFabricHooks {

    private static final List<ClientLifecycleListener> pendingClientListeners =
            new CopyOnWriteArrayList<>();
    private static volatile Consumer<ClientLifecycleListener> clientLifecycleRegistrar =
            listener -> { };
    private static volatile java.util.function.BooleanSupplier clientShutdownSupplier =
            () -> false;

    private MapiFabricHooks() {
    }

    /**
     * Installs the client lifecycle registrar (client source set only) and
     * replays every listener registered before installation.
     *
     * @param registrar consumer receiving the runtime's client listener
     */
    public static void setClientLifecycleRegistrar(Consumer<ClientLifecycleListener> registrar) {
        clientLifecycleRegistrar = java.util.Objects.requireNonNull(registrar, "registrar");
        for (ClientLifecycleListener listener : pendingClientListeners) {
            registrar.accept(listener);
        }
    }

    /**
     * Registers a listener now and replays it if the client registrar is
     * installed later.
     *
     * @param listener the runtime's client lifecycle listener
     */
    static void registerClientLifecycle(ClientLifecycleListener listener) {
        pendingClientListeners.add(listener);
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
