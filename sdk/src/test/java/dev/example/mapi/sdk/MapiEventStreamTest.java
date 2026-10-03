package dev.example.mapi.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MapiEventStreamTest {
    @Test
    void parsesFragmentedUtf8MultilineEventsAndExplicitGaps() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/api/v1/events/stream", exchange -> {
            assertEquals("Bearer stream-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("keepaliveSeconds=1&cursor=7&types=changed&world=test+world", exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                byte[] data = (":event-gap droppedUpTo=4\r\n\r\nid: 5\r\nevent: changed\r\n"
                        + "data: {\"text\":\"café\",\r\ndata: \"ok\":true}\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                for (byte value : data) { output.write(value); output.flush(); }
            }
        });
        server.start();
        try (var stream = new MapiSdkClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "stream-test-token").streamEvents(Optional.of(7L), List.of("changed"),
                        Optional.of("test world"), Duration.ofSeconds(5))) {
            var gap = stream.read().orElseThrow();
            assertTrue(gap.gap());
            assertEquals(4, gap.droppedUpToSeq());
            var event = stream.read().orElseThrow();
            assertEquals("5", event.id());
            assertEquals("changed", event.event());
            assertEquals(Map.of("text", "café", "ok", true), event.data());
            assertTrue(stream.read().isEmpty());
        } finally { server.stop(0); }
    }

    @Test
    void closingStreamReleasesAReaderWaitingForAnEvent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        var finish = new CountDownLatch(1);
        server.createContext("/api/v1/events/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(":ready\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
        try (var stream = new MapiSdkClient("http://127.0.0.1:" + server.getAddress().getPort(),
                "stream-test-token").streamEvents(Optional.empty(), List.of(), Optional.empty(), Duration.ofMillis(200))) {
            long started = System.nanoTime();
            try { assertTrue(stream.read().isEmpty()); } catch (java.net.SocketTimeoutException expected) { }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
        } finally { finish.countDown(); server.stop(0); }
    }
}
