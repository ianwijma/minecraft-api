package dev.example.mapi.internal.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict, dependency-free JSON parser for HTTP request bodies. Mirrors
 * {@link JsonWriter}: objects map to {@link Map} (String keys, insertion
 * order), arrays to {@link List}, integers to {@link Long}, fractional or
 * exponent numbers to {@link Double}, plus {@link String}, {@link Boolean},
 * and {@code null}.
 *
 * <p>Input is bounded ({@value #MAX_INPUT_CHARS} chars by default) and
 * nesting is bounded ({@value #MAX_DEPTH} levels); violations, malformed
 * input, and trailing garbage raise {@link ParseException} with a
 * position-bearing message. Unknown input is never silently coerced.
 */
public final class JsonParser {

    /** Maximum accepted input length in chars. */
    public static final int MAX_INPUT_CHARS = 8192;

    /** Maximum nesting depth (objects and arrays combined). */
    public static final int MAX_DEPTH = 16;

    private final String input;
    private int pos;

    private JsonParser(String input) {
        this.input = input;
    }

    /**
     * Thrown when input is not valid, bounded JSON.
     */
    public static final class ParseException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    /** Public construction for wrapping parse errors at API boundaries. */
    public static ParseException error(String message) {
        return new ParseException(message);
    }
    /**
     * Parses the given JSON text.
     *
     * @param text JSON text, never {@code null}; must be a complete JSON
     *             document (no trailing garbage)
     * @return the parsed value; see the class description for the mapping
     * @throws ParseException on malformed, oversized, or too-deep input
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new ParseException("input must not be null");
        }
        if (text.length() > MAX_INPUT_CHARS) {
            throw new ParseException("input exceeds " + MAX_INPUT_CHARS + " chars");
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.pos != text.length()) {
            throw parser.fail("trailing characters after JSON value");
        }
        return value;
    }

    private ParseException fail(String message) {
        return new ParseException(message + " (at char " + pos + ")");
    }

    private void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                break;
            }
            pos++;
        }
    }

    private char peek() {
        if (pos >= input.length()) {
            throw fail("unexpected end of input");
        }
        return input.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            pos--;
            throw fail("expected '" + c + "'");
        }
    }

    private Object readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw fail("nesting deeper than " + MAX_DEPTH + " levels");
        }
        char c = peek();
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readKeyword("true", Boolean.TRUE);
            case 'f' -> readKeyword("false", Boolean.FALSE);
            case 'n' -> readKeyword("null", null);
            case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> readNumber();
            default -> throw fail("unexpected character '" + c + "'");
        };
    }

    private Object readKeyword(String keyword, Object value) {
        if (input.regionMatches(pos, keyword, 0, keyword.length())) {
            pos += keyword.length();
            return value;
        }
        throw fail("invalid literal");
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
                throw fail("object keys must be strings");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                pos--;
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
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                pos--;
                throw fail("expected ',' or ']'");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                sb.append(readEscape());
            } else if (c < 0x20) {
                throw fail("unescaped control character in string");
            } else {
                sb.append(c);
            }
        }
    }

    private char readEscape() {
        char c = next();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw fail("invalid escape '\\" + c + "'");
        };
    }

    private char readUnicodeEscape() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = next();
            int digit = Character.digit(c, 16);
            if (digit < 0) {
                pos--;
                throw fail("invalid \\u escape");
            }
            value = (value << 4) | digit;
        }
        return (char) value;
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                pos++;
            } else {
                break;
            }
        }
        String raw = input.substring(start, pos);
        if (raw.chars().anyMatch(c -> c == '.' || c == 'e' || c == 'E')) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw fail("invalid number '" + raw + "'");
            }
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(raw);
            } catch (NumberFormatException e2) {
                throw fail("invalid number '" + raw + "'");
            }
        }
    }
}
