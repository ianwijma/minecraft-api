package dev.example.mapi.internal.json;

import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer used for HTTP responses. Emits compact
 * JSON with deterministic key order (map iteration order), which both keeps
 * responses stable and makes them easy to test.
 *
 * <p>Supported value types: {@code null}, {@link Boolean}, {@link Number}
 * ({@link Double} must be finite), {@link String}, {@link Map} with String
 * keys, {@link Iterable}. Anything else is rejected with
 * {@link IllegalArgumentException}.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    /**
     * Serializes the given value to compact JSON.
     *
     * @param value the value to serialize
     * @return the JSON text, never {@code null}
     * @throws IllegalArgumentException on unsupported or non-finite values
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String string) {
            writeString(sb, string);
            return;
        }
        if (value instanceof Boolean bool) {
            sb.append(bool ? "true" : "false");
            return;
        }
        if (value instanceof Double doubleValue) {
            writeDouble(sb, doubleValue);
            return;
        }
        if (value instanceof Float floatValue) {
            writeDouble(sb, floatValue.doubleValue());
            return;
        }
        if (value instanceof Number number) {
            sb.append(number.toString());
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeMap(sb, map);
            return;
        }
        if (value instanceof List<?> list) {
            writeList(sb, list);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            writeIterable(sb, iterable);
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }

    private static void writeDouble(StringBuilder sb, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Non-finite numbers are not valid JSON: " + value);
        }
        sb.append(value);
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings: " + entry.getKey());
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, key);
            sb.append(':');
            writeValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeIterable(StringBuilder sb, Iterable<?> iterable) {
        sb.append('[');
        boolean first = true;
        for (Object item : iterable) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, item);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String string) {
        sb.append('"');
        int length = string.length();
        for (int i = 0; i < length; i++) {
            char c = string.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
