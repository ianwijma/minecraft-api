package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import dev.example.mapi.internal.event.Event;
import dev.example.mapi.internal.event.EventBus;
import dev.example.mapi.internal.event.EventFilter;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Streaming event endpoint: {@code GET /api/v1/events/stream}
 * (spec §13.1). Server-Sent Events with the normal bearer header — no
 * query-string tokens, no stream tickets, no CORS headers. Frames use
 * {@code id}/{@code event}/{@code data}; keepalives and event gaps are sent
 * as comment lines so parsers can skip them while tracking cursor state.
 */
final class EventStreamHandler {

    /** Maximum concurrent streaming connections. */
    static final int MAX_CONCURRENT_STREAMS = 8;

    private static final int REPLAY_PAGE = 200;
    private static final String SSE_CONTENT_TYPE = "text/event-stream; charset=utf-8";

    private final EventBus bus;

    EventStreamHandler(EventBus bus) {
        this.bus = bus;
    }

    record StreamQuery(long cursor, EventFilter filter, long keepaliveMs) {
    }

    StreamQuery parseQuery(String rawQuery) {
        long cursor = bus.latestSeq();
        Set<String> types = new LinkedHashSet<>();
        Optional<String> world = Optional.empty();
        long keepaliveMs = 15_000;
        if (rawQuery != null && !rawQuery.isBlank()) {
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? "" : urlDecode(pair.substring(eq + 1));
                switch (key) {
                    case "cursor" -> {
                        try {
                            cursor = Long.parseLong(value);
                            if (cursor < 0) {
                                throw new NumberFormatException();
                            }
                        } catch (NumberFormatException e) {
                            throw new ProblemException(ProblemCode.BAD_REQUEST,
                                    "cursor must be a non-negative integer");
                        }
                    }
                    case "types" -> {
                        for (String type : value.split(",")) {
                            if (!type.isBlank()) {
                                types.add(type);
                            }
                        }
                    }
                    case "world" -> world = Optional.of(value);
                    case "keepaliveSeconds" -> {
                        try {
                            long seconds = Long.parseLong(value);
                            if (seconds < 1 || seconds > 120) {
                                throw new NumberFormatException();
                            }
                            keepaliveMs = seconds * 1000;
                        } catch (NumberFormatException e) {
                            throw new ProblemException(ProblemCode.BAD_REQUEST,
                                    "keepaliveSeconds must be between 1 and 120");
                        }
                    }
                    default -> {
                        // Unknown stream params are ignored for forward compatibility.
                    }
                }
            }
        }
        return new StreamQuery(cursor, new EventFilter(types, world), keepaliveMs);
    }

    void handle(HttpExchange exchange) throws IOException, InterruptedException {
        StreamQuery query = parseQuery(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().set("Content-Type", SSE_CONTENT_TYPE);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-MAPI-Protocol-Version",
                String.valueOf(HttpApiServer.PROTOCOL_VERSION));
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write("retry: 3000\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        long cursor = query.cursor();
        while (true) {
            EventBus.EventsAfter page = bus.eventsAfter(cursor, query.filter(), REPLAY_PAGE);
            if (page.gap()) {
                writeComment(out, "event-gap droppedUpTo=" + page.oldestAvailableSeq());
            }
            for (Event event : page.events()) {
                writeFrame(out, event);
                cursor = event.seq();
            }
            if (page.events().isEmpty() && !page.gap()) {
                Optional<Event> next = bus.waitForEvent(query.filter(), cursor, query.keepaliveMs());
                if (next.isEmpty()) {
                    writeComment(out, "keepalive " + System.currentTimeMillis());
                } else {
                    writeFrame(out, next.get());
                    cursor = next.get().seq();
                }
            }
        }
    }

    private void writeFrame(OutputStream out, Event event) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("seq", event.seq());
        payload.put("type", event.type());
        payload.put("atEpochMs", event.atEpochMs());
        event.worldSessionId().ifPresent(value -> payload.put("worldSessionId", value));
        payload.put("payload", event.payload());
        StringBuilder frame = new StringBuilder();
        frame.append("id: ").append(event.seq()).append('\n');
        frame.append("event: ").append(event.type()).append('\n');
        frame.append("data: ").append(JsonWriter.write(payload)).append('\n');
        frame.append('\n');
        out.write(frame.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void writeComment(OutputStream out, String text) throws IOException {
        out.write((":" + text + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
