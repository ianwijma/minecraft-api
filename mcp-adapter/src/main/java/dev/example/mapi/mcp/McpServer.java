package dev.example.mapi.mcp;

import dev.example.mapi.internal.json.JsonWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Model Context Protocol server over newline-delimited JSON-RPC 2.0 (stdio).
 * Implements {@code initialize}, {@code tools/list}, and {@code tools/call}
 * with the Phase 1 tool set: observe, capabilities, schema, world, player,
 * lifecycle, task.wait, task.cancel, artifact.get. Every tool maps to
 * documented HTTP endpoints — the adapter adds no authority of its own.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final InstanceClient instance;
    private final Map<String, Function<Map<String, Object>, ToolResult>> tools = new LinkedHashMap<>();
    private final StringBuilder out;

    /**
     * @param instance instance binding
     * @param out      response sink (one JSON-RPC response per line)
     */
    public McpServer(InstanceClient instance, StringBuilder out) {
        this.instance = instance;
        this.out = out;
        registerTools();
    }

    /** Tool execution outcome. */
    public record ToolResult(String text, boolean isError) {
    }

    private void registerTools() {
        tools.put("observe", this::toolObserve);
        tools.put("capabilities", this::toolCapabilities);
        tools.put("schema", this::toolSchema);
        tools.put("world", this::toolWorld);
        tools.put("player", this::toolPlayer);
        tools.put("lifecycle", this::toolLifecycle);
        tools.put("commands", this::toolCommands);
        tools.put("ui", this::toolUi);
        tools.put("task.wait", this::toolTaskWait);
        tools.put("task.cancel", this::toolTaskCancel);
        tools.put("artifact.get", this::toolArtifact);
    }

    private List<Map<String, Object>> toolDescriptors() {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        descriptors.add(descriptor("observe", "Compact situation summary of the running instance", objectSchema()));
        descriptors.add(descriptor("capabilities", "Instance identity, scopes, and availability",
                objectSchema()));
        descriptors.add(descriptor("schema", "Endpoint reference for the current API surface", objectSchema()));
        descriptors.add(descriptor("world", "Read world state: subaction=block|time with dimension/x/y/z",
                objectSchema("subaction", "dimension", "x", "y", "z")));
        descriptors.add(descriptor("player", "List connected players (subaction=list)",
                objectSchema("subaction")));
        descriptors.add(descriptor("lifecycle", "Server/session status (subaction=status)",
                objectSchema("subaction")));
        descriptors.add(descriptor("commands", "Execute a console command (commands.execute scope): command",
                objectSchema("command", "expectedWorldSessionId")));
        descriptors.add(descriptor("ui", "Local client observations: subaction=status|tree (client.control scope)",
                objectSchema("subaction")));
        descriptors.add(descriptor("task.wait", "Wait for a task to finish: taskId, timeoutMs",
                objectSchema("taskId", "timeoutMs")));
        descriptors.add(descriptor("task.cancel", "Request task cancellation: taskId", objectSchema("taskId")));
        descriptors.add(descriptor("artifact.get", "Read a screenshot artifact: name (file name only)",
                objectSchema("name")));
        return descriptors;
    }

    private Map<String, Object> descriptor(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", name);
        descriptor.put("description", description);
        descriptor.put("inputSchema", inputSchema);
        return descriptor;
    }

    private Map<String, Object> objectSchema(String... propertyNames) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : propertyNames) {
            properties.put(name, Map.of("type", "string"));
        }
        schema.put("properties", properties);
        return schema;
    }

    /**
     * Handles one JSON-RPC request line; notifications produce no output.
     *
     * @param line raw request line
     * @return true when the session should continue
     */
    public boolean handleLine(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        Map<String, Object> request = InstanceClient.parseObject(line);
        Object id = request.get("id");
        String method = request.get("method") instanceof String m ? m : null;
        if (method == null) {
            return true;
        }
        Map<String, Object> params = request.get("params") instanceof Map
                ? InstanceClient.parseObject(JsonWriter.write(request.get("params")))
                : Map.of();
        switch (method) {
            case "initialize" -> respond(id, result()
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("capabilities", Map.of("tools", Map.of()))
                    .put("serverInfo", Map.of("name", "mapi-mcp-adapter", "version",
                            dev.example.mapi.internal.MapiBootstrap.modVersion()))
                    .build());
            case "tools/list" -> respond(id, Map.of("tools", toolDescriptors()));
            case "tools/call" -> respond(id, callTool(params));
            case "ping" -> respond(id, Map.of());
            default -> {
                if (id != null) {
                    respondError(id, -32601, "method not found: " + method);
                }
            }
        }
        return true;
    }

    private Map<String, Object> callTool(Map<String, Object> params) {
        String name = params.get("name") instanceof String n ? n : null;
        Map<String, Object> arguments = params.get("arguments") instanceof Map
                ? InstanceClient.parseObject(JsonWriter.write(params.get("arguments")))
                : Map.of();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of());
        if (name == null || !tools.containsKey(name)) {
            response.put("isError", true);
            response.put("content", List.of(textContent("unknown tool: " + name)));
            return response;
        }
        try {
            ToolResult result = tools.get(name).apply(arguments);
            response.put("isError", result.isError());
            response.put("content", List.of(textContent(result.text())));
        } catch (UncheckedIOException e) {
            response.put("isError", true);
            response.put("content", List.of(textContent("tool failed: " + e.getCause().getMessage())));
        } catch (RuntimeException e) {
            response.put("isError", true);
            response.put("content", List.of(textContent("tool failed: " + e.getMessage())));
        }
        return response;
    }

    private static Map<String, Object> textContent(String text) {
        return Map.of("type", "text", "text", text);
    }

    private ToolResult toolObserve(Map<String, Object> args) {
        return httpGetTool("/api/v1/ready", null);
    }

    private ToolResult toolCapabilities(Map<String, Object> args) {
        return httpGetTool("/api/v1/info", null);
    }

    private ToolResult toolSchema(Map<String, Object> args) {
        String schema = """
                Phase 1 HTTP surface (GET unless noted):
                  /api/v1/health, /live, /ready, /time, /info, /events?after=
                  /api/v1/server/status, /server/players?fields=&limit=&offset=
                  /api/v1/server/world/block?dimension=&x=&y=&z=
                  /api/v1/server/world/time?dimension=
                  /api/v1/tasks [GET, POST], /api/v1/tasks/{id} [GET, DELETE]
                  /api/v1/leases [GET, POST], /api/v1/leases/{id} [DELETE],
                    /api/v1/leases/{id}/renew [POST]
                  /api/v1/client/status, /client/screen/tree
                  /api/v1/client/input/key [POST], /client/screenshot [POST]
                  /api/v1/events/ticket [POST]
                Full schemas: docs/openapi.yaml.""";
        return new ToolResult(schema, false);
    }

    private ToolResult toolWorld(Map<String, Object> args) {
        String subaction = stringArg(args, "subaction", "block");
        return switch (subaction) {
            case "block" -> httpGetTool("/api/v1/server/world/block?dimension="
                    + urlEncode(stringArg(args, "dimension", "minecraft:overworld"))
                    + "&x=" + stringArg(args, "x", "0")
                    + "&y=" + stringArg(args, "y", "0")
                    + "&z=" + stringArg(args, "z", "0"), null);
            case "time" -> httpGetTool("/api/v1/server/world/time?dimension="
                    + urlEncode(stringArg(args, "dimension", "minecraft:overworld")), null);
            default -> new ToolResult("unknown world subaction: " + subaction, true);
        };
    }

    private ToolResult toolPlayer(Map<String, Object> args) {
        if (!"list".equals(stringArg(args, "subaction", "list"))) {
            return new ToolResult("unknown player subaction; use list", true);
        }
        return httpGetTool("/api/v1/server/players", null);
    }

    private ToolResult toolLifecycle(Map<String, Object> args) {
        if (!"status".equals(stringArg(args, "subaction", "status"))) {
            return new ToolResult("lifecycle mutations are not part of the Phase 1 surface", true);
        }
        return httpGetTool("/api/v1/server/status", null);
    }

    private ToolResult toolCommands(Map<String, Object> args) {
        String command = stringArg(args, "command", null);
        if (command == null) {
            return new ToolResult("command is required", true);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        String expected = stringArg(args, "expectedWorldSessionId", null);
        if (expected != null) {
            body.put("expectedWorldSessionId", expected);
        }
        return httpPostTool("/api/v1/server/commands/execute", body);
    }

    private ToolResult toolUi(Map<String, Object> args) {
        String subaction = stringArg(args, "subaction", "status");
        return switch (subaction) {
            case "status" -> httpGetTool("/api/v1/client/status", null);
            case "tree" -> httpGetTool("/api/v1/client/screen/tree", null);
            default -> new ToolResult("unknown ui subaction: " + subaction, true);
        };
    }

    private ToolResult toolTaskWait(Map<String, Object> args) {
        String taskId = stringArg(args, "taskId", null);
        if (taskId == null) {
            return new ToolResult("taskId is required", true);
        }
        long timeoutMs = Long.parseLong(stringArg(args, "timeoutMs", "30000"));
        try {
            String task = instance.waitTask(taskId, timeoutMs);
            return new ToolResult(task, false);
        } catch (IOException | InterruptedException e) {
            return new ToolResult("wait failed: " + e.getMessage(), true);
        }
    }

    private ToolResult toolTaskCancel(Map<String, Object> args) {
        String taskId = stringArg(args, "taskId", null);
        if (taskId == null) {
            return new ToolResult("taskId is required", true);
        }
        return httpDeleteTool("/api/v1/tasks/" + taskId);
    }

    private ToolResult toolArtifact(Map<String, Object> args) {
        String name = stringArg(args, "name", null);
        try {
            var artifact = instance.readScreenshot(name);
            if (artifact == null) {
                return new ToolResult("artifact not found or not readable: " + name, true);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("bytes", artifact.getValue());
            out.put("contentBase64", artifact.getKey());
            return new ToolResult(InstanceClient.json(out), false);
        } catch (IOException e) {
            return new ToolResult("artifact read failed: " + e.getMessage(), true);
        }
    }

    private ToolResult httpGetTool(String path, String unused) {
        try {
            var response = instance.get(path);
            return new ToolResult(response.getValue(), response.getKey() != 200);
        } catch (IOException | InterruptedException e) {
            return new ToolResult("request failed: " + e.getMessage(), true);
        }
    }

    private ToolResult httpPostTool(String path, Map<String, Object> body) {
        try {
            var response = instance.post(path, InstanceClient.json(body));
            return new ToolResult(response.getValue(), response.getKey() != 200);
        } catch (IOException | InterruptedException e) {
            return new ToolResult("request failed: " + e.getMessage(), true);
        }
    }

    private ToolResult httpDeleteTool(String path) {
        try {
            var response = instance.delete(path);
            return new ToolResult(response.getValue(), response.getKey() != 200);
        } catch (IOException | InterruptedException e) {
            return new ToolResult("request failed: " + e.getMessage(), true);
        }
    }

    private static String stringArg(Map<String, Object> args, String name, String fallback) {
        Object value = args.get(name);
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void respond(Object id, Map<String, Object> result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        out.append(InstanceClient.json(response)).append('\n');
    }

    private void respondError(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        out.append(InstanceClient.json(response)).append('\n');
    }

    private static MapBuilder result() {
        return new MapBuilder();
    }

    private static final class MapBuilder {

        private final Map<String, Object> map = new LinkedHashMap<>();

        MapBuilder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        Map<String, Object> build() {
            return map;
        }
    }
}
