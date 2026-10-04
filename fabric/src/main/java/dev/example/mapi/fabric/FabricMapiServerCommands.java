package dev.example.mapi.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.command.MapiControlCommand;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Local console controls; server credentials are never sent to players. */
final class FabricMapiServerCommands {

    private FabricMapiServerCommands() {
    }

    static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->
                register(dispatcher));
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
