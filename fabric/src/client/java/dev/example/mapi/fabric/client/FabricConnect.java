package dev.example.mapi.fabric.client;

import dev.example.mapi.internal.client.ClientBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** Fabric direct connection (spec §9.2; allowlist enforced upstream). */
final class FabricConnect implements ClientBridge.ConnectBackend {

    @Override
    public void join(String address) {
        Minecraft client = Minecraft.getInstance();
        ServerAddress serverAddress = ServerAddress.parseString(address);
        ServerData data = new ServerData("mapi-direct", address, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(new TitleScreen(), client, serverAddress,
                data, false, null);
    }
}
