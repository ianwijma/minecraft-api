package dev.example.mapi.internal.encoding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical hashing of typed trees (spec §11.2: defined ordering and
 * normalization for hashing/diffing). Canonical form: compound keys sorted
 * (byte-wise, {@code String.compareTo}), lists in order, longs and long-array
 * elements as decimal strings, floats/doubles via their round-trip string
 * forms, non-finite values as their tagged strings, negative zero preserved.
 * The hash is SHA-256 over the canonical JSON text.
 */
public final class TagHash {

    private TagHash() {
    }

    /**
     * @param tag the value tree, never {@code null}
     * @return lowercase hex SHA-256 of the canonical form
     */
    public static String sha256Hex(Tag tag) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalJson(tag).getBytes(StandardCharsets.UTF_8));
            return hex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param tag the value tree, never {@code null}
     * @return the canonical JSON text (compound keys sorted)
     */
    public static String canonicalJson(Tag tag) {
        return dev.example.mapi.internal.json.JsonWriter.write(canonicalWire(tag));
    }

    private static Object canonicalWire(Tag tag) {
        return switch (tag) {
            case Tag.IntTag value -> TagJson.toWire(value);
            case Tag.LongTag value -> TagJson.toWire(value);
            case Tag.FloatTag value -> TagJson.toWire(value);
            case Tag.DoubleTag value -> TagJson.toWire(value);
            case Tag.ByteArrTag value -> TagJson.toWire(value);
            case Tag.StringTag value -> TagJson.toWire(value);
            case Tag.IntArrTag value -> TagJson.toWire(value);
            case Tag.LongArrTag value -> TagJson.toWire(value);
            case Tag.ListTag value -> {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("type", TagType.LIST.wireName());
                map.put("elementType", value.elementType().wireName());
                map.put("value", value.elements().stream().map(TagHash::canonicalWire).toList());
                yield map;
            }
            case Tag.CompoundTag value -> {
                Map<String, Object> map = new java.util.LinkedHashMap<>();
                map.put("type", TagType.COMPOUND.wireName());
                Map<String, Object> sorted = new TreeMap<>();
                value.entries().forEach((name, child) -> sorted.put(name, canonicalWire(child)));
                map.put("value", sorted);
                yield map;
            }
        };
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
