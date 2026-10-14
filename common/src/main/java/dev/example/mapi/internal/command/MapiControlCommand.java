package dev.example.mapi.internal.command;

import dev.example.mapi.internal.MapiRuntime;
import java.util.function.Consumer;

/** Local command behavior shared by both loader adapters. */
public final class MapiControlCommand {

    /** Runs a local list command; ordinary feedback never includes config credentials. */
    public static int executeAllowList(MapiRuntime runtime, String list, String action,
            String site, Consumer<String> feedback) {
        try {
            runtime.updateAllowList(list, action, site);
            feedback.accept("Minecraft API " + list + ": " + action + " (saved to config).");
            return 1;
        } catch (RuntimeException e) {
            feedback.accept("Minecraft API " + list + " command failed. Use an exact "
                    + (list.equals("allowedOrigins") ? "http(s) origin without a path" : "host[:port] target")
                    + " and check the config and server log.");
            return 0;
        }
    }

    private MapiControlCommand() {
    }

    /** Runs an action, keeping credentials separate from ordinary feedback and errors. */
    public static int execute(MapiRuntime runtime, String action, Consumer<String> feedback,
            Consumer<MapiRuntime.HttpConnection> connectionFeedback) {
        try {
            switch (action) {
                case "status" -> connectionFeedback.accept(runtime.httpConnection());
                case "enable" -> connectionFeedback.accept(runtime.enableHttp());
                case "disable" -> {
                    runtime.disableHttp();
                    feedback.accept("Minecraft API is disabled (saved to config).");
                }
                default -> {
                    feedback.accept("Usage: /mapi <status|enable|disable>");
                    return 0;
                }
            }
            return 1;
        } catch (RuntimeException e) {
            feedback.accept("Minecraft API command failed. Check the config, port, and server log.");
            return 0;
        }
    }
}
