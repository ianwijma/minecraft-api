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
}
