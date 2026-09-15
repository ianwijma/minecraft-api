package dev.example.mapi.neoforge;

import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.encoding.TagType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;


/**
 * Converts Minecraft NBT into the canonical typed representation
 * (docs/data-encoding.md). Lives in the loader module because it touches
 * Minecraft classes (ADR-0001). Unsupported or oversized values are reported
 * as tagged strings ({@code serialization-status} markers), never dropped
 * silently.
 */
final class NbtTags {

    private NbtTags() {
    }

    /** Marker prefix for values that cannot be represented faithfully. */
    static final String UNSUPPORTED_PREFIX = "!unsupported:";

    static Tag toTag(net.minecraft.nbt.Tag nbt) {
        return switch (nbt) {
            case CompoundTag compound -> compound(compound);
            case ListTag list -> list(list);
            case StringTag string -> new Tag.StringTag(string.value());
            case NumericTag numeric -> numeric(numeric);
            case net.minecraft.nbt.ByteArrayTag bytes -> new Tag.ByteArrTag(bytes.getAsByteArray());
            case net.minecraft.nbt.IntArrayTag ints -> new Tag.IntArrTag(ints.getAsIntArray());
            case net.minecraft.nbt.LongArrayTag longs -> new Tag.LongArrTag(longs.getAsLongArray());
            default -> new Tag.StringTag(UNSUPPORTED_PREFIX
                    + nbt.getType().toString());
        };
    }

    private static Tag compound(CompoundTag compound) {
        Map<String, Tag> entries = new LinkedHashMap<>();
        for (Map.Entry<String, net.minecraft.nbt.Tag> entry : compound.entrySet()) {
            try {
                entries.put(entry.getKey(), toTag(entry.getValue()));
            } catch (RuntimeException e) {
                entries.put(entry.getKey(), new Tag.StringTag(
                        UNSUPPORTED_PREFIX + "conversion-failed"));
            }
        }
        return new Tag.CompoundTag(entries);
    }

    private static Tag list(ListTag list) {
        TagType elementType = TagType.STRING;
        if (!list.isEmpty()) {
            elementType = typeOf(list.get(0));
        }
        List<Tag> elements = new ArrayList<>(list.size());
        for (net.minecraft.nbt.Tag element : list) {
            elements.add(toTag(element));
        }
        return new Tag.ListTag(elementType, elements);
    }

    private static Tag numeric(net.minecraft.nbt.Tag nbt) {
        if (nbt instanceof NumericTag numeric) {
            return switch (nbt.getId()) {
                case net.minecraft.nbt.Tag.TAG_BYTE -> new Tag.IntTag(TagType.BYTE, numeric.byteValue());
                case net.minecraft.nbt.Tag.TAG_SHORT -> new Tag.IntTag(TagType.SHORT, numeric.shortValue());
                case net.minecraft.nbt.Tag.TAG_INT -> new Tag.IntTag(TagType.INT, numeric.intValue());
                case net.minecraft.nbt.Tag.TAG_LONG -> new Tag.LongTag(numeric.longValue());
                case net.minecraft.nbt.Tag.TAG_FLOAT -> new Tag.FloatTag(numeric.floatValue());
                default -> new Tag.DoubleTag(numeric.doubleValue());
            };
        }
        return new Tag.StringTag(UNSUPPORTED_PREFIX + "non-numeric");
    }

    private static TagType typeOf(net.minecraft.nbt.Tag nbt) {
        return switch (nbt) {
            case CompoundTag ignored -> TagType.COMPOUND;
            case ListTag ignored -> TagType.LIST;
            case StringTag ignored -> TagType.STRING;
            case net.minecraft.nbt.ByteArrayTag ignored -> TagType.BYTE_ARRAY;
            case net.minecraft.nbt.IntArrayTag ignored -> TagType.INT_ARRAY;
            case net.minecraft.nbt.LongArrayTag ignored -> TagType.LONG_ARRAY;
            case NumericTag numeric -> switch (nbt.getId()) {
                case net.minecraft.nbt.Tag.TAG_BYTE -> TagType.BYTE;
                case net.minecraft.nbt.Tag.TAG_SHORT -> TagType.SHORT;
                case net.minecraft.nbt.Tag.TAG_INT -> TagType.INT;
                case net.minecraft.nbt.Tag.TAG_LONG -> TagType.LONG;
                case net.minecraft.nbt.Tag.TAG_FLOAT -> TagType.FLOAT;
                default -> TagType.DOUBLE;
            };
            default -> TagType.STRING;
        };
    }

    /** @return true when the tree contains an unsupported marker */
    static boolean hasUnsupported(Tag tag) {
        if (tag instanceof Tag.StringTag string && string.value().startsWith(UNSUPPORTED_PREFIX)) {
            return true;
        }
        if (tag instanceof Tag.ListTag list) {
            return list.elements().stream().anyMatch(NbtTags::hasUnsupported);
        }
        if (tag instanceof Tag.CompoundTag compound) {
            return compound.entries().values().stream().anyMatch(NbtTags::hasUnsupported);
        }
        return false;
    }
}
