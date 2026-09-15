package dev.example.mapi.internal.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, bounded JSON parser for request bodies (complement to
 * {@link JsonWriter}). Produces {@code Map}/{@code List}/{@code String}/
 * {@code Long}/{@code Double}/{@code Boolean}/{@code null}. Non-finite
 * literals and trailing garbage are rejected; size and depth are bounded so
 * parsing can never blow up before game-thread work.
 */
public final class JsonReader {

    /** Default parser limits. */
    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 1000;

    private final String text;
    private final int maxDepth;
    private final int maxNodes;
    private int pos;
    private int nodes;

    private JsonReader(String text, int maxDepth, int maxNodes) {
        this.text = text;
        this.maxDepth = maxDepth;
        this.maxNodes = maxNodes;
    }

    /**
     * Parses a complete JSON document.
     *
     * @param text UTF-8 JSON text, never {@code null}
     * @return the parsed value ({@code Map}, {@code List}, {@code String},
     *     {@code Long}, {@code Double}, {@code Boolean}, or {@code null})
     * @throws IllegalArgumentException on malformed, oversized, or
     *     over-deep input
     */
    public static Object parse(String text) {
        return parse(text, MAX_DEPTH, MAX_NODES);
    }

    /**
     * Parses with explicit limits.
     *
     * @param text     JSON text, never {@code null}
     * @param maxDepth maximum nesting depth
     * @param maxNodes maximum number of parsed values
     * @return the parsed value
     * @throws IllegalArgumentException on malformed, oversized, or over-deep
     *     input
     */
    public static Object parse(String text, int maxDepth, int maxNodes) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty JSON body");
        }
        JsonReader reader = new JsonReader(text.strip(), maxDepth, maxNodes);
        reader.skipWhitespace();
        Object value = reader.readValue(0);
        reader.skipWhitespace();
        if (reader.pos != reader.text.length()) {
            throw reader.fail("trailing characters after JSON value");
        }
        return value;
    }

    /**
     * Parses UTF-8 bytes.
     *
     * @param bytes request body
     * @return the parsed value
     * @throws IllegalArgumentException on malformed input
     */
    public static Object parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private Object readValue(int depth) {
        if (depth > maxDepth) {
            throw fail("nesting exceeds " + maxDepth);
        }
        if (pos >= text.length()) {
            throw fail("unexpected end of input");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject(int depth) {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw fail("expected string key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object value = readValue(depth + 1);
            map.put(key, value);
            bump();
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return map;
            } else {
                throw fail("expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray(int depth) {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth + 1));
            bump();
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return list;
            } else {
                throw fail("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw fail("unterminated string");
            }
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= text.length()) {
                    throw fail("unterminated escape");
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw fail("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw fail("bad escape: \\" + esc);
                }
            } else if (c < 0x20) {
                throw fail("raw control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean floaty = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                floaty = floaty || c == '.' || c == 'e' || c == 'E';
                pos++;
            } else {
                break;
            }
        }
        String number = text.substring(start, pos);
        if (number.isEmpty() || number.equals("-")) {
            throw fail("expected a value");
        }
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            throw fail("malformed number: " + number);
        }
        try {
            if (floaty) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw fail("malformed number: " + number);
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (pos + literal.length() <= text.length()
                && text.regionMatches(pos, literal, 0, literal.length())) {
            pos += literal.length();
            return value;
        }
        throw fail("invalid literal");
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw fail("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= text.length() || text.charAt(pos) != c) {
            throw fail("expected '" + c + "'");
        }
        pos++;
    }

    private void bump() {
        nodes++;
        if (nodes > maxNodes) {
            throw fail("more than " + maxNodes + " JSON values");
        }
    }

    private IllegalArgumentException fail(String message) {
        return new IllegalArgumentException(message + " (at offset " + pos + ")");
    }
}
