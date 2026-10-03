package dev.example.mapi.fabric.client;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;

final class ConnectionHookVerifier {

    private ConnectionHookVerifier() {}

    static void requireActive() {
        try {
            ClassLoader loader = ConnectScreen.class.getClassLoader();
            requireMarker(ConnectScreen.class, loader);
            requireMarker(Class.forName("net.minecraft.client.gui.screens.ConnectScreen$1", false, loader), loader);
            requireMarker(ServerNameResolver.class, loader);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException(
                    "Required MAPI client connection safety mixins are not active", failure);
        }
    }

    private static void requireMarker(Class<?> target, ClassLoader loader) throws ReflectiveOperationException {
        Class.forName(target.getName(), false, loader);
        target.getDeclaredMethod("mapi$connectionPolicyHookApplied");
    }
}
