package dev.example.mapi.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON parser for the runner (self-contained: the runner
 * depends only on the HTTP contract, never on MAPI internals — ADR-0002).
 * Objects preserve key order; numbers map to Long/Double.
 */
final class MiniJson {

    private MiniJson() {
    }

    /**
     * Parses a complete JSON document.
     *
     * @param text JSON text, never {@code null}
     * @return {@code Map}, {@code List}, {@code String}, {@code Long},
     *     {@code Double}, {@code Boolean}, or {@code null}
     * @throws IllegalArgumentException on malformed input
     */
    static Object parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty JSON");
        }
        Parser parser = new Parser(text.strip());
        parser.skipWs();
        Object value = parser.value(0);
        parser.skipWs();
        if (parser.pos != parser.text.length()) {
            throw parser.fail("trailing characters");
        }
        return value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Object value(int depth) {
            if (depth > 32) {
                throw fail("nesting too deep");
            }
            char c = peek();
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = string();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, value(depth + 1));
                skipWs();
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

        List<Object> array(int depth) {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(value(depth + 1));
                skipWs();
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

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
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
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw fail("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
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
            if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
                throw fail("malformed number: " + number);
            }
            return floaty ? (Object) Double.parseDouble(number) : (Object) Long.parseLong(number);
        }

        Object literal(String name, Object value) {
            if (text.regionMatches(pos, name, 0, name.length())) {
                pos += name.length();
                return value;
            }
            throw fail("invalid literal");
        }

        void skipWs() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        char peek() {
            if (pos >= text.length()) {
                throw fail("unexpected end of input");
            }
            return text.charAt(pos);
        }

        void expect(char c) {
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw fail("expected '" + c + "'");
            }
            pos++;
        }

        IllegalArgumentException fail(String message) {
            return new IllegalArgumentException(message + " (offset " + pos + ")");
        }
    }
}
