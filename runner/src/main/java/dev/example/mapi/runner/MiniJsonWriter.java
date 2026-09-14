package dev.example.mapi.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON writer for the runner (self-contained counterpart to
 * {@link MiniJson}). Supports the same value types: Map (String keys, key
 * order preserved), List, String, Long/Integer, Double, Boolean, null.
 */
final class MiniJsonWriter {

    private MiniJsonWriter() {
    }

    /**
     * @param value the value to serialize
     * @return compact JSON, never {@code null}
     * @throws IllegalArgumentException on unsupported values
     */
    static String write(Object value) {
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
            if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
                throw new IllegalArgumentException("non-finite number: " + doubleValue);
            }
            sb.append(doubleValue);
            return;
        }
        if (value instanceof Number number) {
            sb.append(number);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
            return;
        }
        if (value instanceof Iterable<?> iterable) {
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
            return;
        }
        throw new IllegalArgumentException(
                "unsupported JSON value type: " + value.getClass().getName());
    }

    private static void writeString(StringBuilder sb, String string) {
        sb.append('"');
        for (int i = 0; i < string.length(); i++) {
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

    /** Convenience: parses, validates shape, and returns the object. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object parsed) {
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return out;
    }

    /** Convenience: converts a List&lt;?&gt; into List&lt;Map&gt; entries. */
    static List<Map<String, Object>> asObjectList(Object parsed) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (parsed instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                out.add(asObject(item));
            }
        }
        return out;
    }
}
