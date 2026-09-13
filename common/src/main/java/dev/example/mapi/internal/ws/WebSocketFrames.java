package dev.example.mapi.internal.ws;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RFC 6455 frame codec. Server-to-client frames are unmasked;
 * client-to-server frames must be masked per the RFC. Text messages are
 * UTF-8; binary data frames are rejected with close code 1003.
 */
public final class WebSocketFrames {

    /** RFC 6455 opcodes. */
    public static final int OP_CONTINUATION = 0x0;
    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    /** Close codes used by this implementation. */
    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_GOING_AWAY = 1001;
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    public static final int CLOSE_INVALID_PAYLOAD = 1007;
    public static final int CLOSE_POLICY = 1008;
    public static final int CLOSE_TOO_BIG = 1009;

    /** Maximum accepted frame payload. */
    public static final int MAX_FRAME_BYTES = 1 << 20;

    /** Maximum accepted reassembled message. */
    public static final int MAX_MESSAGE_BYTES = 64 * 1024;

    private WebSocketFrames() {
    }

    /** Parsed inbound frame. */
    public record Frame(boolean fin, int opcode, byte[] payload) {
    }

    /** Signal for the connection loop: peer initiated close. */
    public static final class CloseSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /** @return the close code sent by the peer */
        public final int code;

        CloseSignal(int code) {
            super("close " + code);
            this.code = code;
        }
    }

    /**
     * Reads one frame from the stream, unmasking client frames.
     *
     * @throws CloseSignal on a close frame
     * @throws IOException on stream errors or protocol violations
     */
    public static Frame readFrame(InputStream in) throws IOException {
        int header = in.read();
        if (header < 0) {
            throw new IOException("stream closed");
        }
        int second = in.read();
        if (second < 0) {
            throw new IOException("truncated frame header");
        }
        boolean fin = (header & 0x80) != 0;
        int opcode = header & 0x0F;
        if ((header & 0x70) != 0) {
            throw protocolError("reserved bits set");
        }
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = readUnsignedShort(in);
        } else if (length == 127) {
            length = readUnsignedLong(in);
        }
        if (length > MAX_FRAME_BYTES) {
            throw new ProtocolException(CLOSE_TOO_BIG, "frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        if (!masked) {
            throw new ProtocolException(CLOSE_PROTOCOL_ERROR, "client frames must be masked");
        }
        byte[] mask = in.readNBytes(4);
        if (mask.length < 4) {
            throw new IOException("truncated mask");
        }
        byte[] payload = in.readNBytes((int) length);
        if (payload.length < length) {
            throw new IOException("truncated payload");
        }
        for (int i = 0; i < payload.length; i++) {
            payload[i] ^= mask[i % 4];
        }
        if (opcode == OP_CLOSE) {
            int code = CLOSE_NORMAL;
            if (payload.length >= 2) {
                code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
            }
            throw new CloseSignal(code);
        }
        return new Frame(fin, opcode, payload);
    }

    /** Protocol violation carrying a close code. */
    public static final class ProtocolException extends IOException {

        private static final long serialVersionUID = 1L;

        /** @return the close code to answer with */
        public final int closeCode;

        ProtocolException(int closeCode, String message) {
            super(message);
            this.closeCode = closeCode;
        }
    }

    private static ProtocolException protocolError(String message) {
        return new ProtocolException(CLOSE_PROTOCOL_ERROR, message);
    }

    private static long readUnsignedShort(InputStream in) throws IOException {
        byte[] data = in.readNBytes(2);
        if (data.length < 2) {
            throw new IOException("truncated length");
        }
        return ((data[0] & 0xFFL) << 8) | (data[1] & 0xFFL);
    }

    private static long readUnsignedLong(InputStream in) throws IOException {
        byte[] data = in.readNBytes(8);
        if (data.length < 8) {
            throw new IOException("truncated length");
        }
        long value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFFL);
        }
        if (value < 0 || value > MAX_FRAME_BYTES) {
            throw new ProtocolException(CLOSE_TOO_BIG, "frame exceeds " + MAX_FRAME_BYTES + " bytes");
        }
        return value;
    }

    /**
     * Writes one server frame (unmasked).
     */
    public static void writeFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        int header = 0x80 | opcode;
        synchronized (out) {
            out.write(header);
            if (payload.length <= 125) {
                out.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                out.write(126);
                out.write((payload.length >> 8) & 0xFF);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((payload.length >> shift) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    /** @return a text frame payload for the message */
    public static byte[] textPayload(String message) {
        return message.getBytes(StandardCharsets.UTF_8);
    }

    /** @return a close frame payload for the code */
    public static byte[] closePayload(int code) {
        return new byte[] {(byte) ((code >> 8) & 0xFF), (byte) (code & 0xFF)};
    }
}
