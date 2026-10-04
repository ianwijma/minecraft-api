package dev.example.mapi.fabric.client;

import dev.example.mapi.api.MapiApi;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.command.MapiControlCommand;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/** Client-local API controls; credentials never travel to a Minecraft server. */
public final class FabricMapiCommands {

    private FabricMapiCommands() {
    }

    /** Registers local commands once during client setup. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            var root = ClientCommands.literal("mapi")
                    .executes(command -> run("status", command.getSource()::sendFeedback));
            for (String action : new String[]{"status", "enable", "disable"}) {
                root.then(ClientCommands.literal(action)
                        .executes(command -> run(action, command.getSource()::sendFeedback)));
            }
            dispatcher.register(root);
        });
    }

    private static int run(String action, Consumer<Component> feedback) {
        return MapiControlCommand.execute((MapiRuntime) MapiApi.require(), action,
                text -> feedback.accept(Component.literal(text)), connection -> {
                    feedback.accept(Component.literal("Minecraft API: "
                            + (connection.enabled() ? "enabled" : "disabled")
                            + " (listener " + (connection.running() ? "running" : "stopped") + ")"));
                    feedback.accept(Component.literal("URL: " + connection.url())
                            .withStyle(style -> style.withClickEvent(
                                    new ClickEvent.CopyToClipboard(connection.url()))));
                    String token = connection.token();
                    if (token == null) {
                        feedback.accept(Component.literal("Bearer token: not configured"));
                    } else if (token.isBlank()) {
                        feedback.accept(Component.literal("Authentication is disabled (blank bearer token).")
                                .withStyle(ChatFormatting.YELLOW));
                    } else {
                        feedback.accept(Component.literal("Bearer token: [Click to copy]")
                                .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                        .withUnderlined(true)
                                        .withClickEvent(new ClickEvent.CopyToClipboard(token))));
                    }
                });
    }
}
