package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.SnapshotResult;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.connection.ConnectionPolicy;
import dev.example.mapi.internal.json.JsonReader;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.operation.ExecutionMode;
import dev.example.mapi.internal.operation.OperationDescriptor;
import dev.example.mapi.internal.operation.OperationGuard;
import dev.example.mapi.internal.operation.OperationRegistry;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.operation.SideEffectClass;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.problem.ProblemJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/** Package-private endpoint subsystem for client and process operations. */
final class ClientApiHandler {

    private final HttpApiServer server;

    ClientApiHandler(HttpApiServer server) {
        this.server = server;
    }

    HttpApiRoutes.PostHandler withApiControl(HttpApiRoutes.PostHandler handler) {
        return (exchange, body, grants) -> {
            String leaseId = HttpApiRequest.stringField(body, "leaseId");
            var context = ConnectionPolicy.captureContext(grants,
                    server.runtime.config().clientConnectAllowlist(),
                    () -> server.requireLease("input", leaseId));
            try {
                ConnectionPolicy.withContext(context, () -> {
                            try {
                                handler.handle(exchange, body, grants);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }

    void handleClientControlLease(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        long ttlSeconds = HttpApiRequest.longField(body, "ttlSeconds", 60);
        if (ttlSeconds < 1 || ttlSeconds > 300) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ttlSeconds must be between 1 and 300");
        }
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        boolean renewalRequested = body.containsKey("leaseId");
        if (renewalRequested && leaseId == null) {
            throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                    "leaseId must identify the current input lease", Map.of("topic", "input"));
        }
        dev.example.mapi.internal.lease.ControlLease lease;
        if (renewalRequested) {
            if (!server.runtime.leases().heldBy("input", leaseId)) {
                throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                        "only the current input lease can be renewed",
                        Map.of("topic", "input", "leaseId", leaseId));
            }
            lease = server.runtime.leases().holderOf("input").orElseThrow(() ->
                    new ProblemException(ProblemCode.LEASE_REQUIRED,
                            "input lease is no longer held", Map.of("topic", "input")));
            if (!lease.id().equals(leaseId)) {
                throw new ProblemException(ProblemCode.LEASE_REQUIRED,
                        "only the current input lease can be renewed",
                        Map.of("topic", "input", "leaseId", leaseId));
            }
            server.runtime.leases().renew(lease, ttlSeconds * 1000);
        } else {
            lease = server.runtime.leases().acquire("input",
                    "http:" + exchange.getRemoteAddress().getAddress(), ttlSeconds * 1000);
        }
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(Map.of("leaseId", lease.id(), "topic", lease.topic(),
                "expiresAtEpochMs", lease.expiresAtEpochMs())));
    }

    void sendInventory(HttpExchange exchange) throws IOException {
        var inv = server.runtime.clientBridge().inventory()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "inventory is not available on this process"));
        var slots = server.runtime.callOnClientThread(inv::inspect);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("containerId", server.runtime.callOnClientThread(inv::containerId));
        out.put("carriedCount", server.runtime.callOnClientThread(inv::carriedCount));
        java.util.List<Map<String, Object>> slotMaps = new java.util.ArrayList<>();
        for (dev.example.mapi.internal.client.ClientBridge.InventoryBackend.SlotNode node : slots) {
            slotMaps.add(node.toMap());
        }
        out.put("slots", slotMaps);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void sendTooltip(HttpExchange exchange) throws IOException {
        var inv = server.runtime.clientBridge().inventory()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "inventory is not available on this process"));
        Map<String, String> params = HttpApiRequest.queryParams(exchange.getRequestURI().getRawQuery());
        int slot;
        try {
            slot = Integer.parseInt(params.getOrDefault("slot", "-1"));
        } catch (NumberFormatException e) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "slot must be an integer");
        }
        List<String> lines = server.runtime.callOnClientThread(() -> inv.tooltip(slot));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("slot", slot);
        out.put("lines", lines);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleRenderedTooltip(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.inventory.read", grants, body);
        var inv = server.runtime.clientBridge().inventory()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "inventory is not available on this process"));
        int slot = (int) HttpApiRequest.longField(body, "slot", -1);
        if (slot < 0) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "slot is required");
        }
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        server.runtime.callOnClientThread(() -> {
            server.requireLease("input", leaseId);
            return null;
        });
        var capture = inv.renderedCapture(slot);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(capture.toMap()));
    }

    void handleInventoryClick(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.inventory.click", grants, body);
        var inv = server.runtime.clientBridge().inventory()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "inventory is not available on this process"));
        int slot = (int) HttpApiRequest.longField(body, "slot", -1);
        int button = (int) HttpApiRequest.longField(body, "button", 0);
        String input = HttpApiRequest.stringField(body, "containerInput") == null
                ? "PICKUP" : HttpApiRequest.stringField(body, "containerInput");
        if (slot < 0) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "slot is required");
        }
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        boolean accepted = server.runtime.callOnClientThread(() -> {
            server.requireLease("input", leaseId);
            return inv.click(slot, button, input);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("slot", slot);
        out.put("button", button);
        out.put("containerInput", input);
        out.put("dispatched", accepted);
        out.put("executionMode", ExecutionMode.CLIENT_LOGIC.wireName());
        out.put("effectVerified", false);
        out.put("note", "click dispatched through client logic; resulting server state is not verified");
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleClick(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.ui.click", grants, body);
        var ui = server.runtime.clientBridge().ui()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "UI dispatch is not available on this process"));
        int x = (int) HttpApiRequest.longField(body, "x", -1);
        int y = (int) HttpApiRequest.longField(body, "y", -1);
        if (x < 0 || y < 0) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "x and y are required");
        }
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        boolean consumed = server.runtime.callOnClientThread(() -> {
            server.requireLease("input", leaseId);
            return ui.click(x, y);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("screenId", ui.screenId());
        out.put("executionMode", ExecutionMode.CLIENT_LOGIC.wireName());
        out.put("consumed", consumed);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleWorldLoad(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.worlds.load", grants, body);
        var worlds = server.runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        String levelId = HttpApiRequest.stringField(body, "levelId");
        if (levelId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        server.runtime.callOnClientThread(() -> {
            try {
                worlds.loadWorld(levelId);
            } catch (Exception e) {
                throw new ProblemException(ProblemCode.INTERNAL, "world load failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("levelId", levelId);
        out.put("note", "world load started asynchronously; poll GET /api/v1/server/world for phase ACTIVE");
        HttpApiResponse.respond(exchange, 202, JsonWriter.write(out));
    }

    void handleWorldCreate(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.worlds.create", grants, body);
        var worlds = server.runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        String levelId = HttpApiRequest.stringField(body, "levelId");
        if (levelId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        Long seed = body.get("seed") instanceof Number number ? number.longValue() : null;
        server.runtime.callOnClientThread(() -> {
            try {
                worlds.createWorld(levelId, HttpApiRequest.stringField(body, "gamemode"), seed);
            } catch (Exception e) {
                if (e instanceof ProblemException problem) {
                    throw problem;
                }
                throw new ProblemException(ProblemCode.INTERNAL, "world create failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("levelId", levelId);
        out.put("note", "world creation started asynchronously; poll GET /api/v1/server/world for phase ACTIVE");
        HttpApiResponse.respond(exchange, 202, JsonWriter.write(out));
    }

    void handleWorldDelete(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.worlds.delete", grants, body);
        var worlds = server.runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        String levelId = HttpApiRequest.stringField(body, "levelId");
        if (levelId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "levelId is required");
        }
        // §14: destructive = grant + explicit intent.
        if (body.get("confirm") != Boolean.TRUE) {
            throw new ProblemException(ProblemCode.DESTRUCTIVE_INTENT_REQUIRED,
                    "deleting a world requires \"confirm\": true");
        }
        server.runtime.callOnClientThread(() -> {
            try {
                worlds.deleteWorld(levelId);
            } catch (Exception e) {
                throw new ProblemException(ProblemCode.INTERNAL, "world delete failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("levelId", levelId);
        out.put("deleted", true);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleConnect(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("client.connect", grants, body);
        var connect = server.runtime.clientBridge().connect()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "direct connection is not available on this process"));
        String address = HttpApiRequest.stringField(body, "address");
        if (address == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "address must be host[:port]");
        }
        java.util.List<String> allowlist = server.runtime.config().clientConnectAllowlist();
        ConnectionTarget target = parseConnectionTarget(address);
        if (!ConnectionPolicy.matchesRequestedTarget(target.host(), target.port(), allowlist)) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "address is not on the client.connect.allowlist",
                    Map.of("address", address));
        }
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        server.runtime.callOnClientThread(() -> {
            server.requireLease("input", leaseId);
            try {
                connect.join(address);
            } catch (ProblemException e) {
                throw e;
            } catch (Exception e) {
                throw new ProblemException(ProblemCode.INTERNAL, "connect failed: " + e);
            }
            return null;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("address", address);
        out.put("note", "connection started; poll GET /api/v1/server/world for phase transitions");
        HttpApiResponse.respond(exchange, 202, JsonWriter.write(out));
    }

    void handleWaypoints(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        var service = server.runtime.movement()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "movement is not available on this process"));
        if (!(body.get("waypoints") instanceof java.util.List<?> rawWaypoints)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "waypoints list is required");
        }
        if (rawWaypoints.isEmpty() || rawWaypoints.size() > 64) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "waypoints must be 1..64");
        }
        var waypoints = new java.util.ArrayList<dev.example.mapi.internal.client.MovementService.Waypoint>();
        for (Object raw : rawWaypoints) {
            if (!(raw instanceof Map<?, ?> waypoint)) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "each waypoint must be an object");
            }
            try {
                double yaw = waypoint.get("yaw") instanceof Number yawNumber
                        ? yawNumber.doubleValue() : 0d;
                double pitch = waypoint.get("pitch") instanceof Number pitchNumber
                        ? pitchNumber.doubleValue() : 0d;
                int ticks = waypoint.get("ticks") instanceof Number ticksNumber
                        ? ticksNumber.intValue() : 20;
                waypoints.add(new dev.example.mapi.internal.client.MovementService.Waypoint(
                        yaw, pitch, ticks));
            } catch (IllegalArgumentException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, e.getMessage());
            }
        }

        long deadline = HttpApiRequest.longField(body, "deadlineEpochMs", System.currentTimeMillis() + 30_000);
        try {
            String leaseId = HttpApiRequest.stringField(body, "leaseId");
            var receipt = service.executeWaypoints(waypoints, grants, deadline,
                    () -> server.requireLease("input", leaseId));
            HttpApiResponse.respond(exchange, 200, JsonWriter.write(receipt));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "waypoints failed: " + e);
        }
    }

    private static ConnectionTarget parseConnectionTarget(String address) {
        String host;
        String portText = null;
        if (address.startsWith("[")) {
            int end = address.indexOf(']');
            if (end < 0) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "address must be host[:port]");
            }
            host = address.substring(1, end);
            String suffix = address.substring(end + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST, "address must be host[:port]");
                }
                portText = suffix.substring(1);
            }
            if (!host.matches("[0-9A-Fa-f:.]+")) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "address must be host[:port]");
            }
        } else {
            int colon = address.lastIndexOf(':');
            if (colon >= 0) {
                if (address.indexOf(':') != colon) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST,
                            "IPv6 addresses must be enclosed in brackets");
                }
                host = address.substring(0, colon);
                portText = address.substring(colon + 1);
            } else {
                host = address;
            }
            if (!host.matches("[A-Za-z0-9.\\-]+")) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "address must be host[:port]");
            }
        }
        int port = 25565;
        if (portText != null) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "address port must be an integer");
            }
            if (port < 1 || port > 65535) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "address port must be 1..65535");
            }
        }
        return new ConnectionTarget(host, port);
    }

    private record ConnectionTarget(String host, int port) {}

    void handleLanPublish(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.lan.publish", grants, body);
        var backend = server.runtime.clientBridge().lan()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "LAN publication is not available on this process (integrated server only)"));
        if (!server.runtime.config().serverLanEnabled()) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "LAN publication is disabled by server.lan.enabled=false (spec §9.3)");
        }
        server.runtime.worldLifecycle().requireActive(java.util.Optional.empty());
        int port = (int) HttpApiRequest.longField(body, "port", 0);
        if (port < 0 || port > 65535) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "port must be 0..65535 (0 = game-assigned)");
        }
        String gamemode = HttpApiRequest.stringField(body, "gamemode");
        boolean cheats = body.get("cheats") instanceof Boolean requested && requested;
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        boolean published = server.runtime.callOnClientThread(() -> {
            server.requireLease(dev.example.mapi.internal.tick.TickControlService.LEASE_TOPIC, leaseId);
            return backend.publish(port, gamemode == null ? "" : gamemode, cheats);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("published", published);
        out.put("note", "the game port is now exposed on LAN independently of API authentication");
        HttpApiResponse.respond(exchange, published ? 200 : 503, JsonWriter.write(out));
    }

    void handleLanStop(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.lan.publish", grants, body);
        var backend = server.runtime.clientBridge().lan()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "LAN publication is not available on this process (integrated server only)"));
        if (!server.runtime.config().serverLanEnabled()) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "LAN publication is disabled by server.lan.enabled=false (spec §9.3)");
        }
        String leaseId = HttpApiRequest.stringField(body, "leaseId");
        boolean unpublished = server.runtime.callOnClientThread(() -> {
            server.requireLease(dev.example.mapi.internal.tick.TickControlService.LEASE_TOPIC, leaseId);
            return backend.unpublish();
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("unpublished", unpublished);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void sendScreenInfo(HttpExchange exchange) throws IOException {
        var ui = server.runtime.clientBridge().ui()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "UI inspection is not available on this process"));
        var widgets = server.runtime.callOnClientThread(ui::widgets);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("screenId", server.runtime.callOnClientThread(ui::screenId));
        java.util.List<Map<String, Object>> widgetMaps = new java.util.ArrayList<>();
        for (dev.example.mapi.internal.client.ClientBridge.UiBackend.WidgetNode node : widgets) {
            widgetMaps.add(node.toMap());
        }
        out.put("widgets", widgetMaps);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void sendWorldList(HttpExchange exchange) throws IOException {
        var worlds = server.runtime.clientBridge().worlds()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world management is not available on this process"));
        try {
            var entries = worlds.listWorlds();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
            java.util.List<Map<String, Object>> worldMaps = new java.util.ArrayList<>();
            for (dev.example.mapi.internal.client.ClientBridge.WorldsBackend.WorldEntry entry : entries) {
                worldMaps.add(entry.toMap());
            }
            out.put("worlds", worldMaps);
            HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "world list failed: " + e);
        }
    }

    void handleShutdown(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("process.shutdown", grants, body);
        boolean accepted = server.runtime.requestProcessShutdown();
        if (!accepted) {
            throw new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                    "graceful shutdown is not implemented on this platform adapter");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("accepted", true);
        out.put("note", "graceful local shutdown requested; process-scoped sessions stay available");
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void sendLogs(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpApiRequest.queryParams(exchange.getRequestURI().getRawQuery());
        long cursor = 0;
        if (params.get("cursor") != null) {
            try {
                cursor = Long.parseLong(params.get("cursor"));
                if (cursor < 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new ProblemException(ProblemCode.BAD_REQUEST, "cursor must be a non-negative integer");
            }
        }
        int limit = HttpApiRequest.intParam(exchange, "limit", 200);
        var logs = server.runtime.logs();
        var result = logs.entriesAfter(cursor, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("attachedAtEpochMs", logs.attachedAtEpochMs());
        out.put("latestSeq", logs.latestSeq());
        if (result.gap()) {
            out.put("gap", true);
            out.put("droppedUpToSeq", result.droppedUpToSeq());
        }
        out.put("cursor", result.newCursor());
        out.put("entries", result.entries().stream()
                .map(dev.example.mapi.internal.logging.LogCaptureService.Entry::toMap).toList());
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void sendClientInfo(HttpExchange exchange) throws IOException {
        var bridge = server.runtime.clientBridge();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("bridgeId", bridge.bridgeId());
        out.put("capabilities", bridge.supportedCapabilities().stream().sorted().toList());
        out.put("input", bridge.input().isPresent());
        out.put("screenshots", bridge.screenshots().isPresent());
        out.put("window", bridge.window().isPresent());
        bridge.input().ifPresent(input -> out.put("inputCoverage", java.util.Map.of(
                "callbackDispatch", input.coverage().callbackDispatch(),
                "keybindingState", input.coverage().keybindingState(),
                "helperPolling", input.coverage().helperPolling(),
                "screenDispatch", input.coverage().screenDispatch(),
                "unsupportedNativePolling", input.coverage().unsupportedNativePolling())));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void sendWindowInfo(HttpExchange exchange) throws IOException {
        var window = server.runtime.clientBridge().window()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "window control is not available on this process"));
        var state = server.runtime.callOnClientThread(window::state);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(windowStateMap(state)));
    }

    void handleWindowSet(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants, String operation) throws IOException {
        var window = server.runtime.clientBridge().window()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "window control is not available on this process"));
        var state = server.runtime.callOnClientThread(() -> switch (operation) {
            case "windowed" -> {
                int width = (int) HttpApiRequest.longField(body, "width", -1);
                int height = (int) HttpApiRequest.longField(body, "height", -1);
                if (width < 320 || width > 3840 || height < 240 || height > 2160) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST,
                            "width must be 320..3840 and height 240..2160");
                }
                yield window.setWindowed(width, height);
            }
            case "fullscreen" -> {
                if (!(body.get("fullscreen") instanceof Boolean fullscreen)) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST, "fullscreen must be a boolean");
                }
                yield window.setFullscreen(fullscreen);
            }
            default -> {
                int scale = (int) HttpApiRequest.longField(body, "guiScale", -1);
                if (scale < 0 || scale > 4) {
                    throw new ProblemException(ProblemCode.BAD_REQUEST, "guiScale must be 0..4");
                }
                yield window.setGuiScale(scale);
            }
        });
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(windowStateMap(state)));
    }

    Map<String, Object> windowStateMap(
            dev.example.mapi.internal.client.ClientBridge.WindowBackend.WindowState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("width", state.width());
        out.put("height", state.height());
        out.put("framebufferWidth", state.framebufferWidth());
        out.put("framebufferHeight", state.framebufferHeight());
        out.put("guiScale", state.guiScale());
        out.put("fullscreen", state.fullscreen());
        out.put("revision", state.revision());
        return out;
    }

    void sendScreenshot(HttpExchange exchange) throws IOException {
        var screenshots = server.runtime.clientBridge().screenshots()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "screenshots are not available on this process"));
        // Phase 1 (client thread): schedule the GPU readback.
        var pending = server.runtime.callOnClientThread(screenshots::beginCapture);
        // Phase 2 (HTTP worker): poll the temp file — the PNG fills on a
        // later frame; blocking the client thread here would deadlock.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(5000);
        byte[] png;
        try {
            png = dev.example.mapi.internal.client.ScreenshotFiles.awaitPng(
                    pending.tempPath(), deadline, 25,
                    "GPU readback did not complete within 5000 ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProblemException(ProblemCode.SERVER_BUSY, "interrupted");
        } finally {
            try {
                Files.deleteIfExists(pending.tempPath());
            } catch (IOException ignored) {
                // cleanup is best-effort; response already built below if read
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        out.put("width", pending.width());
        out.put("height", pending.height());
        out.put("frame", pending.frame());
        out.put("guiScale", pending.guiScale());
        if (pending.screenId() != null) {
            out.put("screenId", pending.screenId());
        }
        out.put("capturedAtEpochMs", System.currentTimeMillis());
        out.put("pngBase64", java.util.Base64.getEncoder().encodeToString(png));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleHoldKey(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        var service = server.runtime.clientActions()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "client actions are not available on this process"));
        if (!(body.get("keyCode") instanceof Number keyCode)
                || !(body.get("ticks") instanceof Number ticks)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST,
                    "keyCode and ticks are required");
        }
        ExecutionMode mode = ExecutionMode.RAW_INPUT;
        if (body.get("executionMode") instanceof String requested) {
            mode = java.util.Arrays.stream(ExecutionMode.values())
                    .filter(value -> value.wireName().equals(requested))
                    .findFirst()
                    .orElseThrow(() -> new ProblemException(ProblemCode.BAD_REQUEST,
                            "unknown executionMode: " + requested));
        }
        long deadline = HttpApiRequest.longField(body, "deadlineEpochMs", System.currentTimeMillis() + 10_000);
        try {
            String leaseId = HttpApiRequest.stringField(body, "leaseId");
            var receipt = service.holdKey(new dev.example.mapi.internal.client.ActionDispatchService.ActionRequest(
                    "hold-key", mode, keyCode.intValue(), ticks.intValue(), deadline), grants,
                    () -> server.requireLease("input", leaseId));
            HttpApiResponse.respond(exchange, 200, JsonWriter.write(receipt));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "hold-key failed: " + e);
        }
    }
}
