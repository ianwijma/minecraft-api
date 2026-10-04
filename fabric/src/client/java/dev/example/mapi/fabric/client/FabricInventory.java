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
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        Screen previous = dev.example.mapi.internal.client.ClientThreadCall.call(
                client::isSameThread, client::execute, () -> {
                    requireInWorld();
                    tooltip(slot);
                    Screen before = client.gui.screen();
                    client.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
                    return before;
                });
        ClientBridge.ScreenshotBackend.PendingCapture pending = null;
        try {
            long frame = dev.example.mapi.internal.client.ClientThreadCall.call(
                    client::isSameThread, client::execute, () -> {
                        var window = client.getWindow();
                        var target = menu().slots.get(slot);
                        int x = (window.getGuiScaledWidth() - 176) / 2 + target.x + 8;
                        int y = (window.getGuiScaledHeight() - 166) / 2 + target.y + 8;
                        double physicalX = (double) x * window.getScreenWidth() / window.getGuiScaledWidth();
                        double physicalY = (double) y * window.getScreenHeight() / window.getGuiScaledHeight();
                        org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.handle(), physicalX, physicalY);
                        var callback = org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window.handle(), null);
                        org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window.handle(), callback);
                        if (callback == null) throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE, "game cursor callback is not installed");
                        callback.invoke(window.handle(), physicalX, physicalY);
                        client.gui.screen().mouseMoved(x, y);
                        return client.getFrameTimeNs();
                    });
            for (int completed = 0; completed < 2;) {
                if (System.nanoTime() >= deadline) throw new ProblemException(ProblemCode.SERVER_BUSY, "tooltip frame deadline exceeded");
                long observed = dev.example.mapi.internal.client.ClientThreadCall.call(
                        client::isSameThread, client::execute, client::getFrameTimeNs);
                if (observed != frame) { completed++; frame = observed; }
                else Thread.sleep(5);
            }
            pending = dev.example.mapi.internal.client.ClientThreadCall.call(
                    client::isSameThread, client::execute, () -> new FabricScreenshots().beginCapture());
            byte[] png = dev.example.mapi.internal.client.ScreenshotFiles.awaitPng(
                    pending.tempPath(), deadline, 5, "tooltip PNG deadline exceeded");
            List<String> lines = dev.example.mapi.internal.client.ClientThreadCall.call(
                    client::isSameThread, client::execute, () -> tooltip(slot));
            return new ClientBridge.InventoryBackend.RenderedTooltip(
                    java.util.Base64.getEncoder().encodeToString(png), pending.width(), pending.height(),
                    slot, lines, pending.frame(), pending.guiScale());
        } catch (IOException failure) {
            throw new ProblemException(ProblemCode.INTERNAL, "tooltip PNG failed: " + failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProblemException(ProblemCode.SERVER_BUSY, "tooltip capture interrupted");
        } finally {
            if (pending != null) try { java.nio.file.Files.deleteIfExists(pending.tempPath()); } catch (IOException ignored) { }
            dev.example.mapi.internal.client.ClientThreadCall.callCleanup(client::isSameThread,
                    client::execute, () -> { client.gui.setScreen(previous); return null; });
        }
    }

    private void requireInWorld() {
        if (client.player == null || client.gameMode == null) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "inventory requires an in-world player");
        }
    }
}
