package dev.example.mapi.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recording and replay with divergence detection (spec §1.1/§16). A recording
 * is JSONL: one line per executed step (request + observed response). Replay
 * re-executes the recorded steps and reports divergences — response status
 * and error code — without ever relabeling an automation failure as
 * infrastructure failure (spec §18).
 */
final class Recorder {

    /** One recorded exchange. */
    record Exchange(int index, String name, String method, String path, String body,
            int status, String errorCode) {

        /** @return the exchange as a JSON line */
        String toJsonLine() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"index\":").append(index)
                    .append(",\"name\":\"").append(json(name)).append('"')
                    .append(",\"method\":\"").append(json(method)).append('"')
                    .append(",\"path\":\"").append(json(path)).append('"');
            if (body != null) {
                sb.append(",\"body\":\"").append(json(body)).append('"');
            }
            sb.append(",\"status\":").append(status);
            if (errorCode != null) {
                sb.append(",\"errorCode\":\"").append(json(errorCode)).append('"');
            }
            sb.append('}');
            return sb.toString();
        }

        private static String json(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /** One divergence found during replay. */
    record Divergence(int index, String name, String kind, String expected, String actual) {

        /** @return the divergence as an ordered map */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("name", name);
            map.put("kind", kind);
            map.put("expected", expected);
            map.put("actual", actual);
            return map;
        }
    }

    /** Replay outcome: re-executed steps plus detected divergences. */
    record ReplayReport(List<Exchange> steps, List<Divergence> divergences, boolean ok) {

        /** @return the report as an ordered map */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("steps", steps.size());
            map.put("divergences", divergences.stream().map(Divergence::toMap).toList());
            return map;
        }
    }

    private final List<Exchange> recorded = new ArrayList<>();

    /** @return an unmodifiable view of the recording so far */
    List<Exchange> recording() {
        return List.copyOf(recorded);
    }

    /**
     * Records one exchange.
     *
     * @param index     step index
     * @param name      step name
     * @param method    HTTP method
     * @param path      request path
     * @param body      request body or {@code null}
     * @param status    observed status
     * @param errorCode observed error code or {@code null}
     */
    void record(int index, String name, String method, String path, String body,
            int status, String errorCode) {
        recorded.add(new Exchange(index, name, method, path, body, status, errorCode));
    }

    /**
     * Writes the recording as JSONL.
     *
     * @param file target file
     * @throws java.io.IOException on write failure
     */
    void write(Path file) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        for (Exchange exchange : recorded) {
            sb.append(exchange.toJsonLine()).append('\n');
        }
        Files.writeString(file, sb.toString());
    }

    /**
     * Loads a JSONL recording.
     *
     * @param file source file
     * @return the recorded exchanges
     * @throws java.io.IOException on read failure
     */
    @SuppressWarnings("unchecked")
    static List<Exchange> load(Path file) throws java.io.IOException {
        List<Exchange> out = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> map = (Map<String, Object>) MiniJson.parse(line);
            out.add(new Exchange(
                    (int) (long) (Long) map.getOrDefault("index", 0L),
                    String.valueOf(map.getOrDefault("name", "")),
                    String.valueOf(map.getOrDefault("method", "GET")),
                    String.valueOf(map.getOrDefault("path", "")),
                    map.get("body") == null ? null : String.valueOf(map.get("body")),
                    (int) (long) (Long) map.getOrDefault("status", 0L),
                    map.get("errorCode") == null ? null : String.valueOf(map.get("errorCode"))));
        }
        return out;
    }

    /**
     * Replays a recording against the client and compares outcomes.
     *
     * @param client HTTP client to replay with
     * @param file   the recording file
     * @return the replay report
     * @throws java.io.IOException when the recording cannot be read
     */
    static ReplayReport replay(MapiClient client, Path file) throws java.io.IOException {
        List<Exchange> expected = load(file);
        List<Divergence> divergences = new ArrayList<>();
        List<Exchange> observed = new ArrayList<>();
        for (Exchange step : expected) {
            MapiClient.Result result = switch (step.method()) {
                case "POST" -> client.post(step.path(), step.body());
                default -> client.get(step.path());
            };
            Exchange actual = new Exchange(step.index(), step.name(), step.method(),
                    step.path(), step.body(), result.status(),
                    result.errorCode().orElse(null));
            observed.add(actual);
            if (actual.status() != step.status()) {
                divergences.add(new Divergence(step.index(), step.name(), "status",
                        String.valueOf(step.status()), String.valueOf(actual.status())));
            } else if (!java.util.Objects.equals(actual.errorCode(), step.errorCode())) {
                divergences.add(new Divergence(step.index(), step.name(), "errorCode",
                        String.valueOf(step.errorCode()), String.valueOf(actual.errorCode())));
            }
        }
        return new ReplayReport(observed, divergences, divergences.isEmpty());
    }
}
