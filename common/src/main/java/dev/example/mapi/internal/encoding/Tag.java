package dev.example.mapi.internal.encoding;

import java.util.List;
import java.util.Map;

/**
 * Type-preserving value tree for the canonical typed representation (spec
 * §11.2). Values are immutable; numeric bounds are validated at construction
 * so invalid trees can never be built — before any game-thread work.
 */
public sealed interface Tag permits
        Tag.IntTag, Tag.LongTag, Tag.FloatTag, Tag.DoubleTag, Tag.ByteArrTag,
        Tag.StringTag, Tag.ListTag, Tag.CompoundTag, Tag.IntArrTag, Tag.LongArrTag {

    /** @return the explicit wire type of this value, never {@code null} */
    TagType type();

    /**
     * Bounded integer: byte, short, or int. The type is part of the value;
     * the JSON integer is range-checked.
     *
     * @param type  BYTE, SHORT, or INT
     * @param value integer value within the type's range
     */
    record IntTag(TagType type, long value) implements Tag {
        public IntTag {
            if (type != TagType.BYTE && type != TagType.SHORT && type != TagType.INT) {
                throw new IllegalArgumentException("IntTag requires byte, short, or int: " + type);
            }
            long min = switch (type) {
                case BYTE -> Byte.MIN_VALUE;
                case SHORT -> Short.MIN_VALUE;
                default -> Integer.MIN_VALUE;
            };
            long max = switch (type) {
                case BYTE -> Byte.MAX_VALUE;
                case SHORT -> Short.MAX_VALUE;
                default -> Integer.MAX_VALUE;
            };
            if (value < min || value > max) {
                throw new IllegalArgumentException(type.wireName() + " value out of range: " + value);
            }
        }

        @Override
        public TagType type() {
            return type;
        }
    }

    /**
     * 64-bit integer, emitted as a decimal string on the wire (spec §11.2).
     *
     * @param value the long value
     */
    record LongTag(long value) implements Tag {
        @Override
        public TagType type() {
            return TagType.LONG;
        }
    }

    /**
     * 32-bit float, stored as float and written with float round-trip
     * precision. Non-finite values are emitted as tagged strings
     * ({@code "NaN"}, {@code "Infinity"}, {@code "-Infinity"}); negative zero
     * is preserved.
     *
     * @param value the float value
     */
    record FloatTag(float value) implements Tag {
        @Override
        public TagType type() {
            return TagType.FLOAT;
        }
    }

    /**
     * 64-bit float with round-trippable representation and the same
     * non-finite/negative-zero rules as floats (spec §11.2).
     *
     * @param value the double value
     */
    record DoubleTag(double value) implements Tag {
        @Override
        public TagType type() {
            return TagType.DOUBLE;
        }
    }

    /**
     * Byte array, emitted as tagged base64.
     *
     * @param data the bytes, defensively copied
     */
    record ByteArrTag(byte[] data) implements Tag {
        public ByteArrTag {
            data = data == null ? new byte[0] : data.clone();
        }

        @Override
        public byte[] data() {
            return data.clone();
        }

        @Override
        public TagType type() {
            return TagType.BYTE_ARRAY;
        }
    }

    /** @param value the string value */
    record StringTag(String value) implements Tag {
        public StringTag {
            value = value == null ? "" : value;
        }

        @Override
        public TagType type() {
            return TagType.STRING;
        }
    }

    /**
     * Homogeneous list with explicit element type (spec §11.2).
     *
     * @param elementType declared element type, never {@code null}
     * @param elements    typed elements; empty lists may declare any type
     */
    record ListTag(TagType elementType, List<Tag> elements) implements Tag {
        public ListTag {
            elementType = java.util.Objects.requireNonNull(elementType, "elementType");
            elements = List.copyOf(elements == null ? List.of() : elements);
            for (Tag element : elements) {
                if (element.type() != elementType) {
                    throw new IllegalArgumentException("list element type mismatch: expected "
                            + elementType.wireName() + " got " + element.type().wireName());
                }
            }
        }

        @Override
        public TagType type() {
            return TagType.LIST;
        }
    }

    /**
     * Compound: a map of names to typed values. Key order is preserved
     * (insertion order); the canonical hashing form sorts keys (spec §11.2).
     *
     * @param entries the named values
     */
    record CompoundTag(Map<String, Tag> entries) implements Tag {
        public CompoundTag {
            entries = entries == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(entries));
        }

        @Override
        public TagType type() {
            return TagType.COMPOUND;
        }
    }

    /** @param values the int values, defensively copied */
    record IntArrTag(int[] values) implements Tag {
        public IntArrTag {
            values = values == null ? new int[0] : values.clone();
        }

        @Override
        public int[] values() {
            return values.clone();
        }

        @Override
        public TagType type() {
            return TagType.INT_ARRAY;
        }
    }

    /** @param values the long values, defensively copied */
    record LongArrTag(long[] values) implements Tag {
        public LongArrTag {
            values = values == null ? new long[0] : values.clone();
        }

        @Override
        public long[] values() {
            return values.clone();
        }

        @Override
        public TagType type() {
            return TagType.LONG_ARRAY;
        }
    }
}
