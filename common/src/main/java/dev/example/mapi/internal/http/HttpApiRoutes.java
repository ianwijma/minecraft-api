package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import dev.example.mapi.internal.json.JsonWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Endpoint paths, handlers, and operation metadata kept together for drift checks. */
final class HttpApiRoutes {

    @FunctionalInterface
    interface GetHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @FunctionalInterface
    interface PostHandler {
        void handle(HttpExchange exchange, Map<String, Object> body,
                java.util.Set<dev.example.mapi.internal.operation.Scope> grants) throws IOException;
    }

    private final HttpApiServer server;
    private final ServerApiHandler serverApi;
    private final ClientApiHandler clientApi;
    private final Map<String, GetHandler> getRoutes = new LinkedHashMap<>();
    private final Map<String, PostHandler> postRoutes = new LinkedHashMap<>();
    private final Map<String, String> postRouteOperations = new LinkedHashMap<>();

    HttpApiRoutes(HttpApiServer server, ServerApiHandler serverApi, ClientApiHandler clientApi) {
        this.server = server;
        this.serverApi = serverApi;
        this.clientApi = clientApi;
        init();
    }

    Map<String, GetHandler> getRoutes() { return getRoutes; }
    Map<String, PostHandler> getPostRoutes() { return postRoutes; }
    Map<String, String> postRouteOperations() { return postRouteOperations; }

    private void init() {
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/control/lease", "client.control.lease");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/lease", "server.ticks.lease");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/freeze", "server.ticks.freeze");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/unfreeze", "server.ticks.unfreeze");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/rate", "server.ticks.rate");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/step", "server.ticks.step");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/step-and-observe", "server.ticks.step-and-observe");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/sprint", "server.ticks.sprint");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/ticks/stop", "server.ticks.stop");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/snapshots", "server.snapshots.capture");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/snapshot-diffs", "server.snapshots.diff");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/commands", "server.commands.dispatch");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/actions/hold-key", "client.actions.hold-key");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/movement/waypoints", "client.movement.waypoints");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/actions/click", "client.ui.click");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/inventory/click", "client.inventory.click");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/inventory/tooltip-rendered", "client.inventory.tooltip-rendered");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/worlds/load", "client.worlds.load");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/worlds/create", "client.worlds.create");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/worlds/delete", "client.worlds.delete");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/connect", "client.connect");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/window/set-windowed", "client.window.set-windowed");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/window/set-fullscreen", "client.window.set-fullscreen");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "client/window/set-gui-scale", "client.window.set-gui-scale");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "process/shutdown", "process.shutdown");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/lan", "server.lan.publish");
        postRouteOperations.put(HttpApiServer.API_PREFIX + "server/lan/stop", "server.lan.publish");

        getRoutes.put(HttpApiServer.API_PREFIX + "health", exchange -> HttpApiResponse.respond(exchange, 200,
                JsonWriter.write(server.health())));
        getRoutes.put(HttpApiServer.API_PREFIX + "info", exchange -> HttpApiResponse.respond(exchange, 200,
                JsonWriter.write(server.info())));
        getRoutes.put(HttpApiServer.API_PREFIX + "operations", exchange -> HttpApiResponse.respond(exchange, 200,
                JsonWriter.write(server.operations().toMap())));
        getRoutes.put(HttpApiServer.API_PREFIX + "server/status", server::sendServerStatus);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/world", serverApi::sendWorldInfo);
        getRoutes.put(HttpApiServer.API_PREFIX + "client", clientApi::sendClientInfo);
        getRoutes.put(HttpApiServer.API_PREFIX + "client/window", clientApi::sendWindowInfo);
        getRoutes.put(HttpApiServer.API_PREFIX + "client/screenshots", clientApi::sendScreenshot);
        getRoutes.put(HttpApiServer.API_PREFIX + "client/screen", clientApi::sendScreenInfo);
        getRoutes.put(HttpApiServer.API_PREFIX + "client/worlds", clientApi::sendWorldList);
        getRoutes.put(HttpApiServer.API_PREFIX + "client/inventory", clientApi::sendInventory);
        getRoutes.put(HttpApiServer.API_PREFIX + "client/inventory/tooltip", clientApi::sendTooltip);
        getRoutes.put(HttpApiServer.API_PREFIX + "logs", clientApi::sendLogs);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/ticks", serverApi::sendTickState);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/queries/players", serverApi::sendPlayerQuery);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/queries/entities", serverApi::sendEntityQuery);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/queries/block", serverApi::sendBlockQuery);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/queries/registries", serverApi::sendRegistryList);
        getRoutes.put(HttpApiServer.API_PREFIX + "server/queries/registry", serverApi::sendRegistryEntries);
        getRoutes.put(HttpApiServer.API_PREFIX + "events/stream", server::streamMiddleware);

        postRoutes.put(HttpApiServer.API_PREFIX + "client/control/lease", clientApi::handleClientControlLease);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/lease", serverApi::handleTickLease);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/freeze", serverApi::handleFreeze);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/unfreeze", serverApi::handleUnfreeze);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/rate", serverApi::handleTickRate);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/step",
                (exchange, body, grants) -> serverApi.handleTickStep(exchange, body, grants, false));
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/step-and-observe",
                (exchange, body, grants) -> serverApi.handleTickStep(exchange, body, grants, true));
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/sprint", serverApi::handleTickSprint);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/ticks/stop", serverApi::handleTickStop);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/snapshots", serverApi::handleSnapshotCapture);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/snapshot-diffs", serverApi::handleSnapshotDiff);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/commands", serverApi::handleCommand);
        postRoutes.put(HttpApiServer.API_PREFIX + "client/actions/hold-key",
                clientApi.withApiControl(clientApi::handleHoldKey));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/movement/waypoints",
                clientApi.withApiControl(clientApi::handleWaypoints));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/actions/click",
                clientApi.withApiControl(clientApi::handleClick));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/inventory/click",
                clientApi.withApiControl(clientApi::handleInventoryClick));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/inventory/tooltip-rendered",
                clientApi.withApiControl(clientApi::handleRenderedTooltip));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/worlds/load", clientApi::handleWorldLoad);
        postRoutes.put(HttpApiServer.API_PREFIX + "client/worlds/create", clientApi::handleWorldCreate);
        postRoutes.put(HttpApiServer.API_PREFIX + "client/worlds/delete", clientApi::handleWorldDelete);
        postRoutes.put(HttpApiServer.API_PREFIX + "client/connect",
                clientApi.withApiControl(clientApi::handleConnect));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/window/set-windowed",
                (exchange, body, grants) -> clientApi.handleWindowSet(exchange, body, grants, "windowed"));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/window/set-fullscreen",
                (exchange, body, grants) -> clientApi.handleWindowSet(exchange, body, grants, "fullscreen"));
        postRoutes.put(HttpApiServer.API_PREFIX + "client/window/set-gui-scale",
                (exchange, body, grants) -> clientApi.handleWindowSet(exchange, body, grants, "gui-scale"));
        postRoutes.put(HttpApiServer.API_PREFIX + "process/shutdown", clientApi::handleShutdown);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/lan", clientApi::handleLanPublish);
        postRoutes.put(HttpApiServer.API_PREFIX + "server/lan/stop", clientApi::handleLanStop);
        if (!postRoutes.keySet().equals(postRouteOperations.keySet())) {
            throw new IllegalStateException("every POST route must declare operation metadata");
        }
    }
}
