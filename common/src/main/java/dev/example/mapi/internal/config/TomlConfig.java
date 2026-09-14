package dev.example.mapi.internal.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal TOML layer for the MAPI configuration file (Fabric side; on
 * NeoForge the config is loader-managed). Supports exactly the constructs
 * MAPI uses: tables ({@code [section]}), string/integer/boolean scalars,
 * string arrays, comments, and blank lines — nothing more. Unknown syntax is
 * rejected with a positioned error rather than silently coerced (§7:
 * unknown ≠ coerced).
 */
public final class TomlConfig {

    private TomlConfig() {
    }

    /** A parse/write failure; the message never contains secret values. */
    public static final class TomlException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TomlException(String message) {
            super(message);
        }
    }

    /** One document entry: dotted key plus value. */
    public record Entry(String key, Object value) {
    }

    /**
     * Parses a TOML document into dotted-key entries in document order
     * ({@code [http]} + {@code enabled = true} yields
     * {@code http.enabled = true}).
     *
     * @param text TOML text
     * @return entries in document order
     * @throws TomlException on malformed input
     */
    public static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        String section = "";
        int line = 0;
        for (String raw : text.split("\n", -1)) {
            line++;
            String content = stripComment(raw).trim();
            if (content.isBlank()) {
                continue;
            }
            if (content.startsWith("[")) {
                if (!content.endsWith("]") || content.length() < 3) {
                    throw new TomlException("line " + line + ": malformed table header");
                }
                section = content.substring(1, content.length() - 1).trim();
                validateSegment(section, line);
                continue;
            }
            int eq = content.indexOf('=');
            if (eq <= 0) {
                throw new TomlException("line " + line + ": expected 'key = value'");
            }
            String key = content.substring(0, eq).trim();
            validateKey(key, line);
            String value = content.substring(eq + 1).trim();
            if (key.isBlank() || value.isBlank()) {
                throw new TomlException("line " + line + ": empty key or value");
            }
            String dotted = section.isBlank() ? key : section + "." + key;
            entries.add(new Entry(dotted, parseValue(value, line)));
        }
        return entries;
    }

    private static Object parseValue(String value, int line) {
        String trimmed = value.trim();
        if (trimmed.equals("true") || trimmed.equals("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            String inner = trimmed.substring(1, trimmed.length() - 1);
            if (inner.contains("\"") || inner.indexOf('\\') >= 0) {
                throw new TomlException("line " + line
                        + ": only plain strings without escapes are supported");
            }
            return inner;
        }
        if (trimmed.matches("-?[0-9]+")) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException e) {
                throw new TomlException("line " + line + ": integer out of range");
            }
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String inner = trimmed.substring(1, trimmed.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            List<Object> items = new ArrayList<>();
            for (String part : inner.split(",")) {
                Object parsed = parseValue(part, line);
                if (!(parsed instanceof String)) {
                    throw new TomlException("line " + line
                            + ": only string arrays are supported");
                }
                items.add(parsed);
            }
            return items;
        }
        throw new TomlException("line " + line + ": unsupported value type (use string, integer, "
                + "boolean, or string array)");
    }

    private static void validateKey(String key, int line) {
        if (!key.matches("[a-zA-Z0-9_-]+")) {
            throw new TomlException("line " + line + ": invalid key '" + key + "'");
        }
    }

    private static void validateSegment(String key, int line) {
        validateKey(key, line);
    }

    private static void validateDotted(String dotted, int line) {
        for (String segment : dotted.split("\\.")) {
            validateKey(segment, line);
        }
    }

    private static String stripComment(String raw) {
        boolean inString = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inString = !inString;
            } else if (c == '#' && !inString) {
                return raw.substring(0, i);
            }
        }
        return raw;
    }

    /**
     * Writes a TOML document. Keys are dotted ({@code http.port}); a new
     * table header is emitted when the section changes. Comments (nullable)
     * are emitted above each key.
     *
     * @param entries  entries in emission order
     * @param comments dotted key to comment lines ({@code null} = none)
     * @param file     target file (parent directories are created)
     * @throws IOException on I/O failure
     * @throws TomlException on malformed keys or values
     */
    public static void write(List<Entry> entries, Map<String, String> comments, Path file)
            throws IOException {
        StringBuilder out = new StringBuilder();
        String lastSection = null;
        for (Entry entry : entries) {
            validateDotted(entry.key(), 0);
            int split = entry.key().lastIndexOf('.');
            String section = split < 0 ? "" : entry.key().substring(0, split);
            String leaf = split < 0 ? entry.key() : entry.key().substring(split + 1);
            if (!section.equals(lastSection)) {
                if (lastSection != null) {
                    out.append('\n');
                }
                out.append('[').append(section).append("]\n");
                lastSection = section;
            }
            String comment = comments == null ? null : comments.get(entry.key());
            if (comment != null && !comment.isBlank()) {
                out.append("# ").append(comment.strip()).append('\n');
            }
            out.append(leaf).append(" = ").append(writeValue(entry.value())).append('\n');
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    private static String writeValue(Object value) {
        if (value instanceof String string) {
            if (string.contains("\"") || string.indexOf('\\') >= 0 || string.indexOf('\n') >= 0) {
                throw new TomlException("string values must not contain quotes, backslashes, "
                        + "or newlines");
            }
            return '"' + string + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                Object item = list.get(i);
                if (!(item instanceof String string)) {
                    throw new TomlException("only string arrays are supported");
                }
                out.append(writeValue(string));
            }
            return out.append(']').toString();
        }
        throw new TomlException("unsupported value type: " + value.getClass().getName());
    }
}
