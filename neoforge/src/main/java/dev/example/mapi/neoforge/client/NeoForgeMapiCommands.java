package dev.example.mapi.neoforge.client;

import dev.example.mapi.api.MapiApi;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.command.MapiControlCommand;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client-local API controls; credentials never travel to a Minecraft server. */
public final class NeoForgeMapiCommands {

    private NeoForgeMapiCommands() {
    }

    /** Registers local commands once during client setup. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> {
            var root = Commands.literal("mapi")
                    .executes(command -> run("status", command.getSource()::sendSystemMessage));
            for (String action : new String[]{"status", "enable", "disable"}) {
                root.then(Commands.literal(action)
                        .executes(command -> run(action, command.getSource()::sendSystemMessage)));
            }
            event.getDispatcher().register(root);
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
