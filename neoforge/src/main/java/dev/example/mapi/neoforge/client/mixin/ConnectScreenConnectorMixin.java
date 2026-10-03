package dev.example.mapi.neoforge.client.mixin;

import dev.example.mapi.internal.connection.ConnectionPolicy;
import java.util.Optional;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.gui.screens.ConnectScreen$1")
abstract class ConnectScreenConnectorMixin {

    @Unique
    private void mapi$connectionPolicyHookApplied() {}

    @Shadow @Final private ConnectScreen this$0;

    @Redirect(
            method = "run()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/resolver/ServerNameResolver;resolveAddress("
                            + "Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"),
            require = 1)
    private Optional<ResolvedServerAddress> mapi$resolveUnderApiContext(
            ServerNameResolver resolver, ServerAddress hostAndPort) {
        return ConnectionPolicy.withConnectionResolver(this.this$0, () -> {
            Optional<ResolvedServerAddress> resolved = resolver.resolveAddress(hostAndPort);
            resolved.ifPresent(address -> ConnectionPolicy.verifyResolvedDestination(
                    address.getHostIp(), address.getPort()));
            return resolved;
        });
    }
}
