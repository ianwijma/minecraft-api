package dev.example.mapi.fabric.client;

import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric inventory backend (spec §10.3): client-logic mode — container
 * clicks dispatch through the game's own server-flow
 * ({@code MultiPlayerGameMode.handleContainerInput}), giving
 * server-confirmed postconditions. Slot indexes are the player inventory
 * menu's (0 = craft result, 1-4 craft grid, 5-8 armor, 9-44 main, 45 =
 * offhand).
 */
final class FabricInventory implements ClientBridge.InventoryBackend {

    private final Minecraft client = Minecraft.getInstance();

    private AbstractContainerMenu menu() {
        if (client.player == null || client.gameMode == null) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "inventory requires an in-world player");
        }
        return client.player.inventoryMenu;
    }

    @Override
    public int containerId() {
        return menu().containerId;
    }

    @Override
    public List<ClientBridge.InventoryBackend.SlotNode> inspect() {
        AbstractContainerMenu menu = menu();
        List<ClientBridge.InventoryBackend.SlotNode> nodes = new ArrayList<>();
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            ItemStack stack = menu.slots.get(slot).getItem();
            if (!stack.isEmpty()) {
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                nodes.add(new ClientBridge.InventoryBackend.SlotNode(
                        slot, id == null ? "unknown" : id.toString(), stack.getCount()));
            }
        }
        return List.copyOf(nodes);
    }

    @Override
    public boolean click(int slot, int button, String containerInput) {
        ContainerInput input;
        try {
            input = ContainerInput.valueOf(containerInput.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "unknown containerInput: " + containerInput
                            + " (valid: PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL)");
        }
        AbstractContainerMenu menu = menu();
        client.gameMode.handleContainerInput(menu.containerId, slot, button, input,
                client.player);
        return true;
    }

    @Override
    public List<String> tooltip(int slot) {
        AbstractContainerMenu menu = menu();
        if (slot < 0 || slot >= menu.slots.size()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "slot " + slot + " out of range (0.." + (menu.slots.size() - 1) + ")");
        }
        ItemStack stack = menu.slots.get(slot).getItem();
        if (stack.isEmpty()) {
            return List.of();
        }
        return net.minecraft.client.gui.screens.Screen.getTooltipFromItem(
                client, stack).stream()
                .map(net.minecraft.network.chat.Component::getString)
                .toList();
    }

    @Override
    public int carriedCount() {
        requireInWorld();
        return menu().getCarried().getCount();
    }

    @Override
    public ClientBridge.InventoryBackend.RenderedTooltip renderedCapture(int slot) {
        requireInWorld();
        var player = Minecraft.getInstance().player;
        Minecraft.getInstance().gui.setScreen(
                new net.minecraft.client.gui.screens.inventory.InventoryScreen(player));
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var window = Minecraft.getInstance().getWindow();
        int guiW = window.getGuiScaledWidth();
        int guiH = window.getGuiScaledHeight();
        int leftPos = (guiW - 176) / 2;
        int topPos = (guiH - 166) / 2;
        AbstractContainerMenu menu = menu();
        net.minecraft.world.inventory.Slot targetSlot = menu.slots.get(slot);
        int absoluteX = leftPos + targetSlot.x + 8;
        int absoluteY = topPos + targetSlot.y + 8;
        Screen screen = Minecraft.getInstance().gui.screen();
        if (screen != null) {
            screen.mouseMoved(absoluteX, absoluteY);
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var screenshots = new FabricScreenshots();
        var pending = screenshots.beginCapture();
        long deadline = System.currentTimeMillis() + 5000;
        byte[] png = new byte[0];
        try {
            while (System.currentTimeMillis() < deadline) {
                long size = java.nio.file.Files.size(pending.tempPath());
                if (size > 0) {
                    png = java.nio.file.Files.readAllBytes(pending.tempPath());
                    break;
                }
                Thread.sleep(25);
            }
        } catch (IOException | InterruptedException e) {
            throw new ProblemException(ProblemCode.INTERNAL,
                    "screenshot capture failed: " + e);
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(pending.tempPath());
            } catch (IOException ignored) {
            }
        }
        ItemStack stack = targetSlot.getItem();
        List<String> lines = stack.isEmpty() ? List.of()
                : net.minecraft.client.gui.screens.Screen.getTooltipFromItem(
                        client, stack).stream()
                        .map(net.minecraft.network.chat.Component::getString)
                        .toList();
        Minecraft.getInstance().gui.setScreen(null);
        String b64 = java.util.Base64.getEncoder().encodeToString(png);
        return new ClientBridge.InventoryBackend.RenderedTooltip(
                b64, pending.width(), pending.height(), slot, lines,
                pending.frame(), pending.guiScale());
    }

    private void requireInWorld() {
        if (client.player == null || client.gameMode == null) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "inventory requires an in-world player");
        }
    }
}
