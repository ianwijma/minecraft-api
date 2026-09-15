package dev.example.mapi.internal.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import dev.example.mapi.internal.config.MapiConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live tests for the SSE event stream endpoint against a real loopback
 * listener. The raw stream is read with a plain socket and parsed with the
 * reference {@link SseParser}, mirroring what SDK streaming helpers must do
 * (spec §13.1).
 */
class EventStreamTest {

    private static final Logger LOG = LoggerFactory.getLogger(EventStreamTest.class);
    private static final String TOKEN = "test-token-0123456789";

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private TestPlatform platform;
    private MapiRuntime runtime;
    private HttpApiServer server;
    private int port;

    private void startServer() throws Exception {
        platform = new TestPlatform(LOG);
        runtime = new MapiRuntime(platform);
        server = new HttpApiServer(
                new MapiConfig(true, freePort(), TOKEN, 10_000), runtime, LOG);
        assertTrue(server.start());
        port = server.boundAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + TOKEN)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String readAvailable(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        try {
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (java.net.SocketTimeoutException e) {
            return "";
        }
    }

    @Test
    void streamRequiresBearerToken() throws Exception {
        startServer();
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/events/stream"))
                .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("\"UNAUTHORIZED\""));
    }

    @Test
    void invalidQueryParametersAreRejected() throws Exception {
        startServer();
        assertEquals(400, get("/api/v1/events/stream?cursor=abc").statusCode());
        assertEquals(400, get("/api/v1/events/stream?keepaliveSeconds=0").statusCode());
    }

    @Test
    void streamsReplayAndLiveEventsWithSseFraming() throws Exception {
        startServer();
        var bus = runtime.eventBus();
        bus.publish("world.loaded", Optional.of("w1"), Map.of("name", "alpha"));
        bus.publish("noise", Optional.empty(), Map.of());

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(10_000);
        InputStream in = socket.getInputStream();
        String request = "GET /api/v1/events/stream?cursor=0&types=world.loaded HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\n"
                + "Authorization: Bearer " + TOKEN + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();

        // Headers.
        String head = readLineBlock(in);
        assertTrue(head.startsWith("HTTP/1.1 200"), head);
        assertTrue(head.contains("text/event-stream"));

        // Replay of the matching event after cursor 1.
        StringBuilder received = new StringBuilder();
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            String chunk = readAvailable(in);
            received.append(chunk);
            if (received.toString().contains("world.loaded")) {
                break;
            }
        }
        String stream = received.toString();
        assertTrue(stream.contains("event: world.loaded"), stream);
        assertTrue(stream.contains("\"name\":\"alpha\""), stream);

        // A live event published after the connection arrives on the stream.
        bus.publish("world.loaded", Optional.of("w2"), Map.of("name", "beta"));
        received.setLength(0);
        until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until) {
            String chunk = readAvailable(in);
            received.append(chunk);
            if (received.toString().contains("beta")) {
                break;
            }
        }
        assertTrue(received.toString().contains("beta"));

        // Non-matching types are not delivered (short read window).
        socket.setSoTimeout(400);
        bus.publish("noise", Optional.empty(), Map.of());
        received.setLength(0);
        until = System.currentTimeMillis() + 600;
        while (System.currentTimeMillis() < until) {
            received.append(readAvailable(in));
        }
        assertTrue(received.toString().contains("noise") == false);
        socket.close();
    }

    private static String readLineBlock(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        // Read until the blank line separating headers from the body.
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                break;
            }
            head.append((char) c);
            if (c == '\n' && prev == '\n') {
                break;
            }
            prev = c;
            if (head.length() > 8192) {
                break;
            }
        }
        return head.toString();
    }
}
