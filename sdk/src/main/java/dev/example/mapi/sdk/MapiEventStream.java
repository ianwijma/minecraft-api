package dev.example.mapi.sdk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Bounded bearer-authenticated SSE stream; callers must close it after use. */
public final class MapiEventStream implements AutoCloseable {
    /** One event or explicit history-gap notice. */
    public record Event(String id, String event, Object data, boolean gap, long droppedUpToSeq) {
    }

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "mapi-sdk-stream-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpURLConnection connection;
    private final ScheduledFuture<?> deadline;
    private BufferedReader reader;
    private volatile boolean closed;

    MapiEventStream(URI uri, String token, Duration timeout) throws IOException {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("stream timeout must be positive");
        connection = (HttpURLConnection) uri.toURL().openConnection();
        int budget = Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(1, timeout.toMillis())));
        connection.setConnectTimeout(Math.min(5000, budget));
        connection.setReadTimeout(budget);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "text/event-stream");
        deadline = DEADLINES.schedule(this::close, budget, TimeUnit.MILLISECONDS);
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                String body = connection.getErrorStream() == null ? "" : new String(
                        connection.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                var parsed = SdkJson.asObject(body);
                var error = parsed.get("error") instanceof Map<?, ?> problem ? problem : Map.of();
                throw new MapiSdkClient.MapiSdkException(status,
                        String.valueOf(error.getOrDefault("code", null)), body);
            }
            if (!String.valueOf(connection.getContentType()).startsWith("text/event-stream")) {
                throw new IOException("expected text/event-stream");
            }
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    /** Reads the next event or gap; EOF/explicit close returns empty. */
    public Optional<Event> read() throws IOException {
        String id = "";
        String event = "message";
        var data = new ArrayList<String>();
        while (!closed) {
            String line;
            try {
                line = reader.readLine();
            } catch (IOException failure) {
                if (closed) return Optional.empty();
                close();
                throw failure;
            }
            if (line == null) { close(); return Optional.empty(); }
            if (line.startsWith(":event-gap droppedUpTo=")) {
                return Optional.of(new Event("", "gap", Map.of(), true,
                        Long.parseLong(line.substring(23))));
            }
            if (line.startsWith(":")) continue;
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    String text = String.join("\n", data);
                    Object parsed;
                    try { parsed = SdkJson.parse(text); } catch (IllegalArgumentException ignored) { parsed = text; }
                    return Optional.of(new Event(id, event, parsed, false, 0));
                }
                id = "";
                event = "message";
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "id" -> id = value;
                case "event" -> event = value.isEmpty() ? "message" : value;
                case "data" -> data.add(value);
                default -> { }
            }
        }
        return Optional.empty();
    }

    /** Cancels the deadline and disconnects the underlying stream. Idempotent. */
    @Override
    public void close() {
        closed = true;
        if (deadline != null) deadline.cancel(false);
        connection.disconnect();
    }
}
