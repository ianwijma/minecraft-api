package dev.example.mapi.internal.sse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Incremental Server-Sent-Events parser (reference implementation of the
 * parsing rules SDK streaming helpers must implement, spec §13.1). Feed
 * arbitrary byte chunks; complete events are returned as they are
 * dispatched. Comment lines ({@code :...}) are skipped. Multi-line
 * {@code data} fields are joined with {@code \n}.
 */
public final class SseParser {

    /**
     * One dispatched SSE event.
     *
     * @param id    last seen {@code id} field, empty when none was sent
     * @param event event type, defaults to {@code message}
     * @param data  data payload, lines joined with {@code \n}; empty when no
     *              data was sent
     */
    public record SseEvent(String id, String event, String data) {
    }

    private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
    private String id = "";
    private String eventType = "message";
    private final StringBuilder data = new StringBuilder();
    private boolean sawData;

    /** Creates a parser with empty state. */
    public SseParser() {
    }

    /**
     * Feeds raw bytes (any chunk boundaries are fine) and returns every event
     * completed by this chunk.
     *
     * @param bytes  raw bytes, never {@code null}
     * @param offset start offset
     * @param length number of bytes to feed
     * @return events completed by this feed, possibly empty
     */
    public List<SseEvent> feed(byte[] bytes, int offset, int length) {
        pending.write(bytes, offset, length);
        List<SseEvent> out = new ArrayList<>();
        byte[] buf = pending.toByteArray();
        int start = 0;
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] != '\n' && buf[i] != '\r') {
                continue;
            }
            int eol = i;
            int next = i + 1;
            if (buf[i] == '\r' && next < buf.length && buf[next] == '\n') {
                next++;
            }
            processLine(buf, start, eol - start, out);
            start = next;
            i = next - 1;
        }
        pending.reset();
        if (start < buf.length) {
            pending.write(buf, start, buf.length - start);
        }
        return out;
    }

    /** @return events completed by the end of a logical stream (no trailing newline required) */
    public List<SseEvent> finish() {
        List<SseEvent> out = new ArrayList<>();
        byte[] buf = pending.toByteArray();
        if (buf.length > 0) {
            processLine(buf, 0, buf.length, out);
        }
        processLine(new byte[0], 0, 0, out);
        return out;
    }

    private void processLine(byte[] bytes, int offset, int length, List<SseEvent> out) {
        String line = new String(bytes, offset, length, StandardCharsets.UTF_8);
        if (line.isEmpty()) {
            if (sawData) {
                out.add(new SseEvent(id, eventType, data.toString()));
            }
            reset();
            return;
        }
        if (line.startsWith(":")) {
            return;
        }
        int colon = line.indexOf(':');
        String field;
        String value;
        if (colon < 0) {
            field = line;
            value = "";
        } else {
            field = line.substring(0, colon);
            value = line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
        }
        switch (field) {
            case "id" -> id = value;
            case "event" -> eventType = value.isEmpty() ? "message" : value;
            case "data" -> {
                if (sawData) {
                    data.append('\n');
                }
                data.append(value);
                sawData = true;
            }
            default -> {
                // retry and unknown fields are ignored per the SSE spec
            }
        }
    }

    private void reset() {
        id = "";
        eventType = "message";
        data.setLength(0);
        sawData = false;
    }
}
