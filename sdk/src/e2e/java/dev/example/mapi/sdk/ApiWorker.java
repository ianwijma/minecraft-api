package dev.example.mapi.sdk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JSON-lines acceptance adapter; every request goes through the Java SDK. */
public final class ApiWorker {
    private ApiWorker() {
    }

    private static synchronized void emit(Map<String, Object> value) {
        System.out.println(SdkJson.write(value));
        System.out.flush();
    }

    /** Runs the acceptance worker until stdin closes. */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Map<String, MapiEventStream> streams = new HashMap<>();
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                var request = SdkJson.asObject(line);
                Object id = request.get("id");
                try {
                    String command = String.valueOf(request.get("command"));
                    String streamId = String.valueOf(request.get("streamId"));
                    if (command.equals("close")) {
                        var stream = streams.remove(streamId);
                        if (stream != null) stream.close();
                        emit(Map.of("id", id, "status", 200, "body", Map.of("closed", true)));
                        continue;
                    }
                    Duration timeout = Duration.ofMillis(((Number) request.getOrDefault("timeoutMs", 30000)).longValue());
                    var client = new MapiSdkClient(String.valueOf(request.get("base")),
                            String.valueOf(request.get("token")), timeout);
                    if (command.equals("subscribe")) {
                        Optional<Long> cursor = request.get("cursor") instanceof Number value
                                ? Optional.of(value.longValue()) : Optional.empty();
                        var stream = client.streamEvents(cursor,
                                (List<String>) request.getOrDefault("types", List.of()),
                                Optional.ofNullable((String) request.get("world")), timeout);
                        streams.put(streamId, stream);
                        emit(Map.of("id", id, "status", 200, "body", Map.of("subscribed", true)));
                        Thread.startVirtualThread(() -> {
                            try {
                                Optional<MapiEventStream.Event> event;
                                while ((event = stream.read()).isPresent()) {
                                    var item = event.orElseThrow();
                                    emit(Map.of("streamId", streamId, "event", Map.of(
                                            "gap", item.gap(), "id", item.id(), "event", item.event(),
                                            "data", item.data(), "droppedUpToSeq", item.droppedUpToSeq())));
                                }
                            } catch (Exception failure) {
                                emit(Map.of("streamId", streamId, "error", failure.toString()));
                            } finally {
                                stream.close();
                                emit(Map.of("streamId", streamId, "closed", true));
                            }
                        });
                    } else {
                        var result = request.get("method").equals("GET")
                                ? client.get(String.valueOf(request.get("path")))
                                : client.post(String.valueOf(request.get("path")),
                                        (Map<String, Object>) request.getOrDefault("body", Map.of()));
                        emit(Map.of("id", id, "status", result.status(), "body", result.body()));
                    }
                } catch (MapiSdkClient.MapiSdkException failure) {
                    emit(Map.of("id", id, "status", failure.getStatus(), "body", Map.of("error",
                            Map.of("code", failure.getCode(), "message", failure.getMessage()))));
                } catch (Exception failure) {
                    emit(Map.of("id", id, "error", failure.toString()));
                }
            }
        } finally {
            streams.values().forEach(MapiEventStream::close);
        }
    }
}
