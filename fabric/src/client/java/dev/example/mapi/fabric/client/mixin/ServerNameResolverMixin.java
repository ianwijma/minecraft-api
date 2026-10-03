package dev.example.mapi.fabric.client.mixin;

import dev.example.mapi.internal.connection.ConnectionPolicy;
import java.util.Optional;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerNameResolver.class)
abstract class ServerNameResolverMixin {

    @Unique
    private void mapi$connectionPolicyHookApplied() {}

    @Redirect(
            method = "resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;resolve("
                            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
                    ordinal = 0),
            require = 1)
    private Optional<ResolvedServerAddress> mapi$checkInitialResolverTarget(
            ServerAddressResolver resolver, ServerAddress address) {
        ConnectionPolicy.assertResolverAddress(address.getHost(), address.getPort());
        return resolver.resolve(address);
    }

    @Redirect(
            method = "resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;resolve("
                            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;",
                    ordinal = 1),
            require = 1)
    private Optional<ResolvedServerAddress> mapi$checkRedirectedResolverTarget(
            ServerAddressResolver resolver, ServerAddress address) {
        ConnectionPolicy.assertResolverAddress(address.getHost(), address.getPort());
        return resolver.resolve(address);
    }
}
