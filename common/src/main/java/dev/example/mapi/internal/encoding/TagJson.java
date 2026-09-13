package dev.example.mapi.internal.encoding;

import dev.example.mapi.internal.json.JsonWriter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the canonical typed representation (spec §11.2) as strict JSON:
 * no bare NaN/Infinity tokens, longs as decimal strings, non-finite floats
 * tagged, arrays tagged, lists typed, compounds as name maps. The writer
 * enforces {@link EncodingLimits} so oversized/deep trees are rejected before
 * any scheduling.
 */
public final class TagJson {

    private TagJson() {
    }

    /**
     * Validates the tree against the limits and serializes it.
     *
     * @param tag    the value tree, never {@code null}
     * @param limits structural limits
     * @return strict JSON text, never {@code null}
     * @throws IllegalArgumentException when a limit is exceeded
     */
    public static String write(Tag tag, EncodingLimits limits) {
        validate(tag, limits);
        return JsonWriter.write(toWire(tag));
    }

    /**
     * Validates without serializing; usable before scheduling game-thread
     * work.
     *
     * @param tag    the value tree, never {@code null}
     * @param limits structural limits
     * @throws IllegalArgumentException when a limit is exceeded
     */
    public static void validate(Tag tag, EncodingLimits limits) {
        countNodes(tag, limits, 1);
    }

    private static int countNodes(Tag tag, EncodingLimits limits, int depth) {
        if (depth > limits.maxDepth()) {
            throw new IllegalArgumentException("encoded payload exceeds maxDepth " + limits.maxDepth());
        }
        if (tag instanceof Tag.StringTag string && string.value().length() > limits.maxStringChars()) {
            throw new IllegalArgumentException("string value exceeds maxStringChars");
        }
        int nodes = 1;
        if (tag instanceof Tag.ListTag list) {
            for (Tag element : list.elements()) {
                nodes += countNodes(element, limits, depth + 1);
                if (nodes > limits.maxNodes()) {
                    throw new IllegalArgumentException("encoded payload exceeds maxNodes");
                }
            }
        } else if (tag instanceof Tag.CompoundTag compound) {
            for (Tag value : compound.entries().values()) {
                nodes += countNodes(value, limits, depth + 1);
                if (nodes > limits.maxNodes()) {
                    throw new IllegalArgumentException("encoded payload exceeds maxNodes");
                }
            }
        }
        return nodes;
    }

    /**
     * Converts the tree to JSON-able maps/strings per the §11.2 table.
     *
     * @param tag the value tree, never {@code null}
     * @return a JSON-able representation
     */
    public static Object toWire(Tag tag) {
        return switch (tag) {
            case Tag.IntTag value -> bounded(value.type(), value.value());
            case Tag.LongTag value -> boundedLong(value.value());
            case Tag.FloatTag value -> floating(TagType.FLOAT, value.value());
            case Tag.DoubleTag value -> floating(TagType.DOUBLE, value.value());
            case Tag.ByteArrTag value -> tagged(TagType.BYTE_ARRAY.wireName(),
                    Base64.getEncoder().encodeToString(value.data()));
            case Tag.StringTag value -> value.value();
            case Tag.ListTag value -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", TagType.LIST.wireName());
                map.put("elementType", value.elementType().wireName());
                map.put("value", value.elements().stream().map(TagJson::toWire).toList());
                yield map;
            }
            case Tag.CompoundTag value -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("type", TagType.COMPOUND.wireName());
                Map<String, Object> entries = new LinkedHashMap<>();
                value.entries().forEach((name, child) -> entries.put(name, toWire(child)));
                map.put("value", entries);
                yield map;
            }
            case Tag.IntArrTag value -> tagged(TagType.INT_ARRAY.wireName(),
                    java.util.Arrays.stream(value.values()).boxed().toList());
            case Tag.LongArrTag value -> tagged(TagType.LONG_ARRAY.wireName(),
                    java.util.Arrays.stream(value.values())
                            .mapToObj(String::valueOf).toList());
        };
    }

    private static Map<String, Object> tagged(String type, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("value", value);
        return map;
    }

    private static Map<String, Object> bounded(TagType type, long value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.wireName());
        map.put("value", value);
        return map;
    }

    private static Map<String, Object> boundedLong(long value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", TagType.LONG.wireName());
        map.put("value", String.valueOf(value));
        return map;
    }

    private static Map<String, Object> floating(TagType type, double value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type.wireName());
        if (Double.isNaN(value)) {
            map.put("value", "NaN");
        } else if (value == Double.POSITIVE_INFINITY) {
            map.put("value", "Infinity");
        } else if (value == Double.NEGATIVE_INFINITY) {
            map.put("value", "-Infinity");
        } else if (type == TagType.FLOAT) {
            map.put("value", new RawNumber(Float.toString((float) value)));
        } else {
            map.put("value", new RawNumber(Double.toString(value)));
        }
        return map;
    }

    /**
     * A JSON number with an exact representation string (Float/Double
     * toString forms are round-trippable; {@code JsonWriter} emits
     * {@code toString()} verbatim).
     */
    private static final class RawNumber extends Number {
        private final String repr;

        private RawNumber(String repr) {
            this.repr = repr;
        }

        @Override
        public String toString() {
            return repr;
        }

        @Override
        public int intValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long longValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public float floatValue() {
            throw new UnsupportedOperationException();
        }

        @Override
        public double doubleValue() {
            throw new UnsupportedOperationException();
        }
    }
}
