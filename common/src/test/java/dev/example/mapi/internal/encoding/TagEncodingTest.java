package dev.example.mapi.internal.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TagEncodingTest {

    @Test
    void boundedIntegersCarryExplicitTypesAndRanges() {
        assertEquals("{\"type\":\"byte\",\"value\":127}",
                TagJson.write(new Tag.IntTag(TagType.BYTE, 127), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"short\",\"value\":-32768}",
                TagJson.write(new Tag.IntTag(TagType.SHORT, Short.MIN_VALUE), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"int\",\"value\":2147483647}",
                TagJson.write(new Tag.IntTag(TagType.INT, Integer.MAX_VALUE), EncodingLimits.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Tag.IntTag(TagType.BYTE, 128));
        assertThrows(IllegalArgumentException.class, () -> new Tag.IntTag(TagType.BYTE, -129));
        assertThrows(IllegalArgumentException.class, () -> new Tag.IntTag(TagType.INT, 1L << 31));
        assertThrows(IllegalArgumentException.class, () -> new Tag.IntTag(TagType.LONG, 1));
    }

    @Test
    void longsAreDecimalStringsOnTheWire() {
        assertEquals("{\"type\":\"long\",\"value\":\"9223372036854775807\"}",
                TagJson.write(new Tag.LongTag(Long.MAX_VALUE), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"long\",\"value\":\"-1\"}",
                TagJson.write(new Tag.LongTag(-1), EncodingLimits.DEFAULT));
    }

    @Test
    void floatsAndDoublesAreRoundTrippableWithTaggedNonFinite() {
        assertEquals("{\"type\":\"double\",\"value\":1.5}",
                TagJson.write(new Tag.DoubleTag(1.5), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"float\",\"value\":0.1}",
                TagJson.write(new Tag.FloatTag(0.1f), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"double\",\"value\":\"NaN\"}",
                TagJson.write(new Tag.DoubleTag(Double.NaN), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"double\",\"value\":\"Infinity\"}",
                TagJson.write(new Tag.DoubleTag(Double.POSITIVE_INFINITY), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"double\",\"value\":\"-Infinity\"}",
                TagJson.write(new Tag.DoubleTag(Double.NEGATIVE_INFINITY), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"float\",\"value\":\"NaN\"}",
                TagJson.write(new Tag.FloatTag(Float.NaN), EncodingLimits.DEFAULT));
        // Strict JSON: no bare NaN/Infinity tokens anywhere.
        assertTrue(!TagJson.write(new Tag.DoubleTag(Double.NaN), EncodingLimits.DEFAULT).contains("NaN}")
                || TagJson.write(new Tag.DoubleTag(Double.NaN), EncodingLimits.DEFAULT)
                        .contains("\"NaN\""));
    }

    @Test
    void negativeZeroIsPreserved() {
        assertEquals("{\"type\":\"double\",\"value\":-0.0}",
                TagJson.write(new Tag.DoubleTag(-0.0d), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"float\",\"value\":-0.0}",
                TagJson.write(new Tag.FloatTag(-0.0f), EncodingLimits.DEFAULT));
        assertNotEquals(TagHash.sha256Hex(new Tag.DoubleTag(0.0)),
                TagHash.sha256Hex(new Tag.DoubleTag(-0.0)));
    }

    @Test
    void arraysUseDeclaredEncodings() {
        byte[] bytes = {0, -1, 127};
        assertEquals("{\"type\":\"byte[]\",\"value\":\"" + Base64.getEncoder().encodeToString(bytes) + "\"}",
                TagJson.write(new Tag.ByteArrTag(bytes), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"int[]\",\"value\":[1,-2,3]}",
                TagJson.write(new Tag.IntArrTag(new int[] {1, -2, 3}), EncodingLimits.DEFAULT));
        assertEquals("{\"type\":\"long[]\",\"value\":[\"1\",\"-2\",\"9223372036854775807\"]}",
                TagJson.write(new Tag.LongArrTag(new long[] {1, -2, Long.MAX_VALUE}),
                        EncodingLimits.DEFAULT));
    }

    @Test
    void listsCarryExplicitElementTypes() {
        Tag list = new Tag.ListTag(TagType.BYTE, List.of(
                new Tag.IntTag(TagType.BYTE, 1), new Tag.IntTag(TagType.BYTE, -1)));
        assertEquals("{\"type\":\"list\",\"elementType\":\"byte\",\"value\":"
                + "[{\"type\":\"byte\",\"value\":1},{\"type\":\"byte\",\"value\":-1}]}",
                TagJson.write(list, EncodingLimits.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new Tag.ListTag(TagType.BYTE, List.of(
                new Tag.IntTag(TagType.BYTE, 1), new Tag.IntTag(TagType.INT, 2))));
    }

    @Test
    void compoundsNestAndStringsAreBare() {
        Map<String, Tag> inner = new LinkedHashMap<>();
        inner.put("name", new Tag.StringTag("sword"));
        Tag compound = new Tag.CompoundTag(Map.of("meta", new Tag.CompoundTag(inner)));
        assertEquals("{\"type\":\"compound\",\"value\":{\"meta\":{\"type\":\"compound\","
                + "\"value\":{\"name\":\"sword\"}}}}",
                TagJson.write(compound, EncodingLimits.DEFAULT));
        assertEquals("plain", TagJson.toWire(new Tag.StringTag("plain")));
    }

    @Test
    void limitsRejectDeepAndOversizedPayloads() {
        Tag deep = new Tag.ListTag(TagType.LIST, List.of());
        for (int i = 0; i < 40; i++) {
            deep = new Tag.ListTag(TagType.LIST, List.of(deep));
        }
        final Tag deepTree = deep;
        assertThrows(IllegalArgumentException.class,
                () -> TagJson.validate(deepTree, EncodingLimits.DEFAULT));

        Tag.IntTag leaf = new Tag.IntTag(TagType.INT, 1);
        List<Tag> many = java.util.stream.IntStream.range(0, 2000)
                .<Tag>mapToObj(i -> leaf).toList();
        Tag bigList = new Tag.ListTag(TagType.INT, many);
        assertThrows(IllegalArgumentException.class,
                () -> TagJson.validate(bigList, new EncodingLimits(32, 100, 1000)));
        TagJson.validate(bigList, EncodingLimits.DEFAULT);

        Tag longString = new Tag.StringTag("y".repeat(2000));
        assertThrows(IllegalArgumentException.class,
                () -> TagJson.validate(longString, new EncodingLimits(32, 100_000, 1000)));
    }

    @Test
    void canonicalHashingSortsCompoundKeys() {
        Tag a = new Tag.CompoundTag(Map.of(
                "a", new Tag.IntTag(TagType.INT, 1),
                "b", new Tag.ListTag(TagType.INT, List.of(new Tag.IntTag(TagType.INT, 2)))));
        Tag b = new Tag.CompoundTag(new LinkedHashMap<>(Map.of(
                "b", new Tag.ListTag(TagType.INT, List.of(new Tag.IntTag(TagType.INT, 2))),
                "a", new Tag.IntTag(TagType.INT, 1))));
        assertEquals(TagHash.sha256Hex(a), TagHash.sha256Hex(b));
        assertEquals(TagHash.sha256Hex(a), TagHash.sha256Hex(a));
        assertNotEquals(TagHash.sha256Hex(a),
                TagHash.sha256Hex(new Tag.CompoundTag(Map.of(
                        "a", new Tag.IntTag(TagType.INT, 1),
                        "b", new Tag.ListTag(TagType.INT, List.of(new Tag.IntTag(TagType.INT, 3)))))));
        assertEquals(64, TagHash.sha256Hex(a).length());
    }

    @Test
    void itemComponentsDistinguishAbsentInheritedAndRemoved() {
        Map<String, ItemComponents.Component> components = new LinkedHashMap<>();
        components.put("minecraft:damage", new ItemComponents.Component("minecraft:damage",
                ItemComponents.State.PRESENT, Optional.of(new Tag.IntTag(TagType.INT, 7)),
                ItemComponents.SerializationStatus.FULL));
        components.put("minecraft:enchantable", new ItemComponents.Component("minecraft:enchantable",
                ItemComponents.State.DEFAULT_INHERITED, Optional.empty(),
                ItemComponents.SerializationStatus.PARTIAL));
        components.put("minecraft:unbreakable", new ItemComponents.Component("minecraft:unbreakable",
                ItemComponents.State.REMOVED, Optional.empty(),
                ItemComponents.SerializationStatus.FULL));

        ItemComponents.ItemStackView view = new ItemComponents.ItemStackView(
                "minecraft:iron_sword", 1, components, Optional.of("reg-abc"));
        String json = dev.example.mapi.internal.json.JsonWriter.write(view.toMap());
        assertTrue(json.contains("\"state\":\"present\""));
        assertTrue(json.contains("\"state\":\"default-inherited\""));
        assertTrue(json.contains("\"state\":\"removed\""));
        assertTrue(json.contains("\"registryContext\":\"reg-abc\""));
        assertTrue(json.contains("\"value\":{\"type\":\"int\",\"value\":7}"));

        assertThrows(IllegalArgumentException.class, () -> new ItemComponents.Component(
                "x", ItemComponents.State.PRESENT, Optional.empty(),
                ItemComponents.SerializationStatus.FULL));
        assertThrows(IllegalArgumentException.class, () -> new ItemComponents.Component(
                "x", ItemComponents.State.REMOVED, Optional.of(new Tag.IntTag(TagType.INT, 1)),
                ItemComponents.SerializationStatus.FULL));
    }

    @Test
    void encodingMetadataCarriesVersionContext() {
        EncodingMetadata meta = new EncodingMetadata(EncodingMetadata.CURRENT_ENCODING_VERSION,
                "26.2", Optional.of(4321), Optional.of("fp-1"), Optional.empty());
        String json = dev.example.mapi.internal.json.JsonWriter.write(meta.toMap());
        assertTrue(json.contains("\"encodingVersion\":1"));
        assertTrue(json.contains("\"minecraftVersion\":\"26.2\""));
        assertTrue(json.contains("\"dataVersion\":4321"));
        assertTrue(json.contains("\"registryFingerprint\":\"fp-1\""));
        assertTrue(!json.contains("adapterSchemaVersion"));
    }
}
