package dev.example.mapi.internal.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end WebSocket event tests using the JDK's own WebSocket client.
 * Covers handshake auth (header + single-use ticket), hello/subscribe flow,
 * live delivery, resume, and slow-consumer gap signaling.
 */
class WebSocketServerTest {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketServerTest.class);
    private static final String TOKEN = "test-token-0123456789";

    private final HttpClient client = HttpClient.newHttpClient();
    private TestPlatform platform;
    private MapiRuntime runtime;
    private int wsPort;

    private void startInstance() throws Exception {
        startInstance(Map.of());
    }

    private void startInstance(Map<String, String> extraConfig) throws Exception {
        Path instanceDir = Files.createTempDirectory("mapi-ws-test");
        Files.createDirectories(instanceDir.resolve("config"));
        int port;
        try (var socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        StringBuilder config = new StringBuilder("http.enabled=true\nhttp.port=" + port
                + "\nhttp.token=" + TOKEN + "\n");
        extraConfig.forEach((k, v) -> config.append(k).append('=').append(v).append('\n'));
        Files.writeString(instanceDir.resolve("config").resolve(MapiConfig.CONFIG_FILE_NAME), config.toString());
        platform = new TestPlatform(LOG) {
            @Override
            public Path configDir() {
                return instanceDir.resolve("config");
            }

            @Override
            public Path gameDir() {
                return instanceDir;
            }
        };
        runtime = new MapiRuntime(platform);
        assertTrue(runtime.httpRunning());
        wsPort = runtime.wsBoundPort();
        assertTrue(wsPort > 0, "WS event port must be bound when the API is enabled");
    }

    @AfterEach
    void stopInstance() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }

    private Collector connect(String... headers) {
        WebSocket.Builder builder = client.newWebSocketBuilder();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        Collector collector = new Collector();
        WebSocket webSocket = builder.buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/"), collector).join();
        collector.webSocket = webSocket;
        return collector;
    }

    private Collector connectWithTicket(String ticket) {
        Collector collector = new Collector();
        WebSocket webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/?ticket=" + ticket), collector).join();
        collector.webSocket = webSocket;
        return collector;
    }

    /** Listener collecting full text messages in order. */
    private static final class Collector implements WebSocket.Listener {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket webSocket;
        StringBuilder pending = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) {
                messages.add(pending.toString());
                pending.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }

    private String awaitMessage(Collector collector, long timeoutMs) throws InterruptedException {
        String message = collector.messages.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (message == null) {
            throw new AssertionError("no message within " + timeoutMs + " ms");
        }
        return message;
    }

    @Test
    void helloSubscribeAndLiveDelivery() throws Exception {
        startInstance();
        Collector collector = connect("Authorization", "Bearer " + TOKEN);
        String hello = awaitMessage(collector, 5000);
        assertTrue(hello.contains("\"type\":\"hello\""), hello);
        assertTrue(hello.contains("\"processSessionId\":\"" + runtime.processSessionId() + "\""), hello);
        assertTrue(hello.contains("\"headSeq\":"), hello);

        collector.webSocket.sendText("{\"type\":\"subscribe\",\"after\":0}", true).join();
        String ack = null;
        boolean sawReplay = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String message = collector.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            if (message.contains("\"type\":\"subscribed\"")) {
                ack = message;
                break;
            }
            if (message.contains("\"type\":\"event\"")) {
                sawReplay = true;
            }
        }
        assertTrue(ack != null, "expected a subscribed ack");
        assertTrue(ack.contains("\"policy\":\"drop-oldest\""), ack);
        assertTrue(sawReplay, "after=0 must replay retained history");

        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        boolean sawLive = false;
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String message = collector.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message != null && message.contains("\"eventType\":\"server.starting\"")) {
                sawLive = true;
                break;
            }
        }
        assertTrue(sawLive, "lifecycle events must be delivered live after subscribe");
        collector.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
    }

    @Test
    void connectWithoutCredentialsIsRejected() throws Exception {
        startInstance();
        Collector collector = new Collector();
        CompletionException e = assertThrows(CompletionException.class,
                () -> client.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/"), collector).join());
        assertTrue(e.getCause() instanceof WebSocketHandshakeException, String.valueOf(e.getCause()));
    }

    @Test
    void ticketAuthIsSingleUse() throws Exception {
        startInstance();
        java.net.http.HttpRequest ticketRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + runtime.httpBoundPort() + "/api/v1/events/ticket"))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();
        java.net.http.HttpResponse<String> ticketResponse =
                client.send(ticketRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ticketResponse.statusCode(), ticketResponse.body());
        String ticket = ticketResponse.body().replaceAll(".*\"ticket\":\"([^\"]+)\".*", "$1");

        Collector first = connectWithTicket(ticket);
        String hello = awaitMessage(first, 5000);
        assertTrue(hello.contains("\"type\":\"hello\""), hello);
        first.webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();

        Collector second = new Collector();
        assertThrows(CompletionException.class,
                () -> client.newWebSocketBuilder()
                        .buildAsync(URI.create("ws://127.0.0.1:" + wsPort + "/?ticket=" + ticket), second)
                        .join());
    }

    @Test
    void resumeWithGapWhenHistoryWasEvicted() throws Exception {
        startInstance();
        for (int i = 0; i < 1100; i++) {
            runtime.eventLog().publish("bulk." + i, "instrumented", Map.of("i", i));
        }
        Collector collector = connect("Authorization", "Bearer " + TOKEN);
        awaitMessage(collector, 5000);
        collector.webSocket.sendText("{\"type\":\"subscribe\",\"after\":0}", true).join();
        boolean sawGap = false;
        long firstEventSeq = -1;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && firstEventSeq < 0) {
            String message = collector.messages.poll(500, TimeUnit.MILLISECONDS);
            if (message == null) {
                continue;
            }
            if (message.contains("\"type\":\"gap\"")) {
                sawGap = true;
            }
        }
        assertTrue(sawGap, "resuming from an evicted cursor must signal a gap");
    }

    @Test
    void eventsPollingEndpointReturnsHistoryWithMetadata() throws Exception {
        startInstance();
        long headBefore = runtime.eventLog().headSeq();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + runtime.httpBoundPort() + "/api/v1/events?after=0"))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build();
        java.net.http.HttpResponse<String> response =
                client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"events\":[{"), response.body());
        assertTrue(response.body().contains("\"eventType\":\"api.started\""), response.body());
        assertTrue(response.body().contains("\"headSeq\":" + headBefore), response.body());
        assertTrue(response.body().contains("\"oldestSeq\":1"), response.body());
    }
}
