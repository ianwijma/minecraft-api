package dev.example.mapi.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end MCP adapter tests against a live in-process MAPI instance
 * (runtime + HTTP server + discovery + token file) — the same path the
 * stdio adapter takes in production.
 */
class McpServerTest {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerTest.class);
    private static final String TOKEN = "mcp-adapter-token-0123456789";

    private MapiRuntime runtime;
    private Path gameDir;

    private InstanceClient startInstance() throws Exception {
        gameDir = Files.createTempDirectory("mapi-mcp-test");
        Files.createDirectories(gameDir.resolve("config"));
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Files.writeString(gameDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME),
                "http.enabled=true\nhttp.port=" + port + "\n");
        Files.createDirectories(gameDir.resolve("mcapi"));
        Files.writeString(gameDir.resolve("mcapi").resolve("token"), TOKEN + "\n");
        TestPlatform platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return gameDir.resolve("config");
            }

            @Override
            public Path gameDir() {
                return gameDir;
            }
        };
        runtime = new MapiRuntime(platform);
        assertTrue(runtime.httpRunning());
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        return InstanceClient.fromGameDir(gameDir);
    }

    @AfterEach
    void stopInstance() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /** Sends JSON-RPC lines and returns the parsed responses in order. */
    private Map<String, Object> exchange(InstanceClient instance, String requestLine) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Main.run(instance, new ByteArrayInputStream(requestLine.getBytes(StandardCharsets.UTF_8)), out);
        String response = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(!response.isEmpty(), "adapter must answer");
        return InstanceClient.parseObject(response);
    }

    /** Extracts the text content of a tools/call result. */
    private static String toolText(Map<String, Object> response) {
        Map<String, Object> result = InstanceClient.parseObject(
                dev.example.mapi.internal.json.JsonWriter.write(response.get("result")));
        if (result.get("content") instanceof List<?> content && !content.isEmpty()
                && content.getFirst() instanceof Map<?, ?> first) {
            return String.valueOf(first.get("text"));
        }
        return dev.example.mapi.internal.json.JsonWriter.write(result);
    }

    @Test
    void initializeAndListTools() throws Exception {
        InstanceClient instance = startInstance();
        Map<String, Object> initialize = exchange(instance, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """);
        Map<String, Object> result = InstanceClient.parseObject(
                dev.example.mapi.internal.json.JsonWriter.write(initialize.get("result")));
        assertEquals("2024-11-05", result.get("protocolVersion"));

        Map<String, Object> list = exchange(instance, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """);
        String tools = dev.example.mapi.internal.json.JsonWriter.write(list.get("result"));
        assertTrue(tools.contains("observe"), tools);
        assertTrue(tools.contains("task.wait"), tools);
        assertTrue(tools.contains("artifact.get"), tools);
    }

    @Test
    void toolCallsReachTheInstance() throws Exception {
        InstanceClient instance = startInstance();
        Map<String, Object> observe = exchange(instance,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"observe\",\"arguments\":{}}}");
        String observeText = toolText(observe);
        assertTrue(observeText.contains("\"readiness\":\"worldReady\""), observeText);
        Map<String, Object> observeResult = InstanceClient.parseObject(
                dev.example.mapi.internal.json.JsonWriter.write(observe.get("result")));
        assertEquals(Boolean.FALSE, observeResult.get("isError"));

        Map<String, Object> players = exchange(instance,
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"player\",\"arguments\":{\"subaction\":\"list\"}}}");
        String playersText = toolText(players);
        assertTrue(playersText.contains("\"name\":\"Asha\""), playersText);
    }

    @Test
    void taskWaitAndUnknownTools() throws Exception {
        InstanceClient instance = startInstance();
        Map<String, Object> created = exchange(instance,
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"lifecycle\",\"arguments\":{\"subaction\":\"status\"}}}");
        String statusText = toolText(created);
        assertTrue(statusText.contains("\"running\":true"), statusText);

        Map<String, Object> unknown = exchange(instance,
                "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}");
        String unknownText = toolText(unknown);
        assertTrue(unknownText.contains("unknown tool"), unknownText);

        Map<String, Object> methodMissing = exchange(instance,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"bogus/method\",\"params\":{}}");
        assertEquals(-32601L, (Long) InstanceClient.parseObject(
                dev.example.mapi.internal.json.JsonWriter.write(methodMissing.get("error"))).get("code"));
    }
}
