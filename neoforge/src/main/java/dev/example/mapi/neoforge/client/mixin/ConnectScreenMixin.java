package dev.example.mapi.neoforge.client.mixin;

import dev.example.mapi.internal.connection.ConnectionPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {

    @Unique
    private void mapi$connectionPolicyHookApplied() {}

    @Inject(
            method = "connect(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;"
                    + "Lnet/minecraft/client/multiplayer/ServerData;Lnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"),
            require = 1)
    private void mapi$captureApiConnection(Minecraft minecraft, ServerAddress hostAndPort,
            ServerData serverData, TransferState transferState, CallbackInfo callback) {
        ConnectionPolicy.captureConnect(this, minecraft, hostAndPort.getHost(), hostAndPort.getPort(),
                transferState != null);
    }
}
