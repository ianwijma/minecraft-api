package dev.example.mapi.internal.ws;

import dev.example.mapi.internal.events.EventLog;
import dev.example.mapi.internal.events.EventLog.EventRecord;
import dev.example.mapi.internal.json.JsonParser;
import dev.example.mapi.internal.json.JsonWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * One WebSocket connection: an event subscription with an outbound queue,
 * a writer/pump thread with heartbeat, and a reader loop on the caller's
 * thread. Implements the slow-consumer policies from spec §3.3
 * ({@code drop-oldest} with a GAP marker, or {@code disconnect}).
 *
 * <p>Wire messages are JSON objects: {@code hello} on connect,
 * {@code subscribe}/{@code subscribed}, {@code event}, {@code gap}, and
 * frame-level {@code ping}/{@code pong}.
 */
public final class WebSocketConnection {

    /** Outbound queue bound before the slow-consumer policy applies. */
    public static final int QUEUE_CAPACITY = 256;

    /** Heartbeat ping interval in ms (writer thread poll timeout). */
    public static final long HEARTBEAT_MS = 20_000;

    /** Policy chosen at subscribe time. */
    public enum Policy {
        DROP_OLDEST, DISCONNECT;

        /** @return the wire name */
        public String wireName() {
            return this == DROP_OLDEST ? "drop-oldest" : "disconnect";
        }

        /**
         * @param name wire name
         * @return the policy or {@code null} when unknown
         */
        public static Policy fromWire(String name) {
            if ("drop-oldest".equals(name)) {
                return DROP_OLDEST;
            }
            if ("disconnect".equals(name)) {
                return DISCONNECT;
            }
            return null;
        }
    }

    /** Application behavior injected by the owner (HttpApiServer/runtime). */
    public interface Listener {

        /**
         * Called once after the handshake, before the pump starts.
         *
         * @param connection the connection
         */
        void onOpen(WebSocketConnection connection);

        /**
         * Handles a client text message.
         *
         * @param connection the connection
         * @param text       the message
         */
        void onMessage(WebSocketConnection connection, String text);

        /**
         * Called once when the connection loop exits.
         *
         * @param connection the connection
         */
        void onClose(WebSocketConnection connection);
    }

    private final InputStream input;
    private final OutputStream output;
    private final Logger logger;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LinkedBlockingQueue<String> outbound = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private volatile EventLog.Subscription subscription;
    private volatile Policy policy = Policy.DROP_OLDEST;
    private volatile long droppedEvents;
    private Thread pumpThread;

    /**
     * @param input    socket input (reader runs on the calling thread)
     * @param output   socket output
     * @param listener application listener
     * @param logger   platform logger
     */
    public WebSocketConnection(InputStream input, OutputStream output, Listener listener, Logger logger) {
        this.input = input;
        this.output = output;
        this.listener = listener;
        this.logger = logger;
    }

    /**
     * Sends a text message. Non-blocking: the message joins the outbound
     * queue; when the queue is full the configured slow-consumer policy
     * applies. No-op after close.
     *
     * @param message JSON text
     * @return true when queued
     */
    public boolean sendText(String message) {
        if (closed.get()) {
            return false;
        }
        if (!outbound.offer(message)) {
            if (policy == Policy.DISCONNECT) {
                close(WebSocketFrames.CLOSE_POLICY, "slow consumer");
                return false;
            }
            if (outbound.poll() != null) {
                droppedEvents++;
            }
            boolean queued = outbound.offer(message);
            return queued;
        }
        return true;
    }

    /**
     * Sets the slow-consumer policy (subscribe-time).
     *
     * @param newPolicy the policy, never {@code null}
     */
    public void setPolicy(Policy newPolicy) {
        this.policy = newPolicy;
    }

    /**
     * @return the wire name of the current slow-consumer policy
     */
    public String policyWireName() {
        return policy.wireName();
    }

    /**
     * Registers the event subscription used by the pump.
     *
     * @param newSubscription subscription handle
     */
    public void setSubscription(EventLog.Subscription newSubscription) {
        this.subscription = newSubscription;
    }

    /**
     * Runs the writer/pump loop until the connection closes. Drains the
     * outbound queue, emits GAP markers after drops, and sends heartbeat
     * pings while idle. Call on a dedicated thread.
     */
    public void runPump() {
        pumpThread = Thread.currentThread();
        try {
            while (!closed.get()) {
                String message = outbound.poll(HEARTBEAT_MS, TimeUnit.MILLISECONDS);
                if (message == null) {
                    if (closed.get()) {
                        break;
                    }
                    WebSocketFrames.writeFrame(output, WebSocketFrames.OP_PING,
                            "mapi-heartbeat".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    continue;
                }
                if (droppedEvents > 0) {
                    Map<String, Object> gap = new LinkedHashMap<>();
                    gap.put("type", "gap");
                    gap.put("lost", droppedEvents);
                    WebSocketFrames.writeFrame(output, WebSocketFrames.OP_TEXT,
                            WebSocketFrames.textPayload(JsonWriter.write(gap)));
                    droppedEvents = 0;
                }
                WebSocketFrames.writeFrame(output, WebSocketFrames.OP_TEXT,
                        WebSocketFrames.textPayload(message));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.debug("MAPI WS: pump I/O ended: {}", e.toString());
        } finally {
            closeQuietly();
            listener.onClose(this);
            EventLog.Subscription sub = subscription;
            if (sub != null) {
                sub.unsubscribe();
            }
        }
    }

    /**
     * Runs the reader loop (call on the connection's accept thread). Replies
     * to pings, reassembles fragmentation, and forwards text messages to the
     * listener.
     */
    public void runReader() {
        StringBuilder assembled = null;
        int assembledOpcode = -1;
        try {
            while (!closed.get()) {
                WebSocketFrames.Frame frame = WebSocketFrames.readFrame(input);
                if (frame.opcode() == WebSocketFrames.OP_PING) {
                    WebSocketFrames.writeFrame(output, WebSocketFrames.OP_PONG, frame.payload());
                    continue;
                }
                if (frame.opcode() == WebSocketFrames.OP_PONG) {
                    continue;
                }
                if (frame.opcode() == WebSocketFrames.OP_BINARY) {
                    throw new WebSocketFrames.ProtocolException(WebSocketFrames.CLOSE_INVALID_PAYLOAD,
                            "binary frames are not accepted");
                }
                if (frame.opcode() == WebSocketFrames.OP_TEXT || frame.opcode() == WebSocketFrames.OP_CONTINUATION) {
                    if (frame.opcode() == WebSocketFrames.OP_TEXT) {
                        if (assembled != null) {
                            throw new WebSocketFrames.ProtocolException(
                                    WebSocketFrames.CLOSE_PROTOCOL_ERROR, "unexpected continuation");
                        }
                        if (frame.fin()) {
                            listener.onMessage(this, new String(frame.payload(),
                                    java.nio.charset.StandardCharsets.UTF_8));
                            continue;
                        }
                        assembled = new StringBuilder(new String(frame.payload(),
                                java.nio.charset.StandardCharsets.UTF_8));
                        assembledOpcode = WebSocketFrames.OP_TEXT;
                    } else {
                        if (assembled == null) {
                            throw new WebSocketFrames.ProtocolException(
                                    WebSocketFrames.CLOSE_PROTOCOL_ERROR, "continuation without start");
                        }
                        assembled.append(new String(frame.payload(),
                                java.nio.charset.StandardCharsets.UTF_8));
                        if (assembled.length() > WebSocketFrames.MAX_MESSAGE_BYTES) {
                            throw new WebSocketFrames.ProtocolException(
                                    WebSocketFrames.CLOSE_TOO_BIG, "message too large");
                        }
                        if (frame.fin()) {
                            String text = assembled.toString();
                            assembled = null;
                            assembledOpcode = -1;
                            listener.onMessage(this, text);
                        }
                    }
                    if (assembledOpcode != WebSocketFrames.OP_TEXT && assembled != null) {
                        throw new WebSocketFrames.ProtocolException(
                                WebSocketFrames.CLOSE_PROTOCOL_ERROR, "binary continuation");
                    }
                    continue;
                }
                throw new WebSocketFrames.ProtocolException(
                        WebSocketFrames.CLOSE_PROTOCOL_ERROR, "unsupported opcode " + frame.opcode());
            }
        } catch (WebSocketFrames.CloseSignal e) {
            try {
                WebSocketFrames.writeFrame(output, WebSocketFrames.OP_CLOSE,
                        WebSocketFrames.closePayload(e.code));
            } catch (IOException ignored) {
                // Socket already gone.
            }
        } catch (WebSocketFrames.ProtocolException e) {
            try {
                WebSocketFrames.writeFrame(output, WebSocketFrames.OP_CLOSE,
                        WebSocketFrames.closePayload(e.closeCode));
            } catch (IOException ignored) {
                // Socket already gone.
            }
        } catch (IOException e) {
            logger.debug("MAPI WS: reader I/O ended: {}", e.toString());
        } finally {
            closeQuietly();
        }
    }

    /**
     * Closes the connection: sends a close frame, unregisters, and stops the
     * pump. Idempotent.
     */
    public void close(int code, String reason) {
        if (closed.compareAndSet(false, true)) {
            try {
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("type", "closing");
                note.put("code", code);
                note.put("reason", reason);
                WebSocketFrames.writeFrame(output, WebSocketFrames.OP_TEXT, WebSocketFrames.textPayload(
                        JsonWriter.write(note)));
                WebSocketFrames.writeFrame(output, WebSocketFrames.OP_CLOSE,
                        WebSocketFrames.closePayload(code));
            } catch (IOException ignored) {
                // Socket already gone.
            }
            Thread pump = pumpThread;
            if (pump != null) {
                pump.interrupt();
            }
        }
    }

    private void closeQuietly() {
        closed.set(true);
        try {
            input.close();
        } catch (IOException ignored) {
            // Socket already gone.
        }
        try {
            output.close();
        } catch (IOException ignored) {
            // Socket already gone.
        }
    }

    /**
     * @return true once the connection is closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Parses a client message; helper for {@link Listener#onMessage}.
     *
     * @param text raw message
     * @return the parsed map
     * @throws JsonParser.ParseException on malformed input
     */
    public static Map<String, Object> parseMessage(String text) {
        Object parsed;
        try {
            parsed = JsonParser.parse(text);
        } catch (RuntimeException e) {
            throw e;
        }
        if (!(parsed instanceof Map)) {
            throw JsonParser.error("messages must be JSON objects");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    /**
     * Renders an event record into the wire event object.
     *
     * @param record the record
     * @return the JSON-serializable event map
     */
    public static Map<String, Object> eventObject(EventRecord record) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", record.seq());
        event.put("eventType", record.type());
        event.put("source", record.source());
        event.put("wallClock", record.wallClockEpochMs());
        event.put("monotonicNanos", record.monotonicNanos());
        event.put("processSessionId", record.processSessionId());
        if (record.worldSessionId() != null) {
            event.put("worldSessionId", record.worldSessionId());
        }
        event.put("data", record.data());
        return event;
    }

    /**
     * Renders an event record into the WebSocket wire format.
     *
     * @param record the record
     * @return JSON text
     */
    public static String eventJson(EventRecord record) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "event");
        message.put("event", eventObject(record));
        return JsonWriter.write(message);
    }
}
