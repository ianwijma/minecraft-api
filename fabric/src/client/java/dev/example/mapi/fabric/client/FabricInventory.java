package dev.example.mapi.fabric.client;

import dev.example.mapi.internal.client.ClientBridge;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
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
    public int carriedCount() {
        requireInWorld();
        return menu().getCarried().getCount();
    }

    private void requireInWorld() {
        if (client.player == null || client.gameMode == null) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "inventory requires an in-world player");
        }
    }
}
