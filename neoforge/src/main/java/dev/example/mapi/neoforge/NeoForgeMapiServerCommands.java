package dev.example.mapi.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.command.MapiControlCommand;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Local console controls; server credentials are never sent to players. */
final class NeoForgeMapiServerCommands {

    private NeoForgeMapiServerCommands() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> register(event.getDispatcher()));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("mapi")
                .requires(source -> source.getEntity() == null
                        && "Server".equals(source.getTextName())
                        && Commands.hasPermission(Commands.LEVEL_OWNERS).test(source))
                .executes(command -> run(command.getSource(), "status"));
        for (String action : new String[]{"status", "enable", "disable"}) {
            root.then(Commands.literal(action)
                    .executes(command -> run(command.getSource(), action)));
        }
        dispatcher.register(root);
    }

    private static int run(CommandSourceStack source, String action) {
        return MapiControlCommand.execute((MapiRuntime) MapiApi.require(), action,
                text -> source.sendSuccess(() -> Component.literal(text), false), connection -> {
                    source.sendSuccess(() -> Component.literal("Minecraft API: "
                            + (connection.enabled() ? "enabled" : "disabled")
                            + " (listener " + (connection.running() ? "running" : "stopped") + ")"), false);
                    source.sendSuccess(() -> Component.literal("URL: " + connection.url()), false);
                    String token = connection.token();
                    String authentication = token == null ? "Bearer token: not configured"
                            : token.isBlank() ? "Authentication is disabled (blank bearer token)."
                            : "Bearer token: " + token;
                    source.sendSuccess(() -> Component.literal(authentication), false);
                });
    }
}
