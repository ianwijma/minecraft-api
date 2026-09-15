package dev.example.mapi.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonReaderTest {

    @Test
    void parsesObjectsArraysAndScalars() {
        assertEquals(Map.of("a", 1L), JsonReader.parse("{\"a\":1}"));
        assertEquals(java.util.Arrays.asList(1L, 2.5, true, false, null, "x"),
                JsonReader.parse("[1, 2.5, true, false, null, \"x\"]"));
        assertEquals("hello", JsonReader.parse("\"hello\""));
        assertEquals(42L, JsonReader.parse("42"));
        assertEquals(1.5, JsonReader.parse("1.5"));
        assertEquals(Boolean.TRUE, JsonReader.parse("true"));
        assertNull(JsonReader.parse("null"));
        assertEquals(-7L, JsonReader.parse("-7"));
        assertEquals(1.0E3, JsonReader.parse("1.0e3"));
    }

    @Test
    void parsesEscapesAndUnicode() {
        assertEquals("quote:\" back:\\ slash:/ nl:\n tab:\t uni:A",
                JsonReader.parse("\"quote:\\\" back:\\\\ slash:\\/ nl:\\n tab:\\t uni:\\u0041\""));
    }

    @Test
    void preservesKeyOrderAndWhitespace() {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) JsonReader.parse(
                "\n { \"b\" : 1 ,\n \"a\" : [ 2 , 3 ] } \t ");
        assertEquals(List.of("b", "a"), List.copyOf(map.keySet()));
        assertEquals(List.of(2L, 3L), map.get("a"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(""));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("{\"a\"}"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("[1,]"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("\"unterminated"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("nul"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("01x"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("{} trailing"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("{\"a\":1}\\"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("\"\\q\""));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("truex"));
    }

    @Test
    void rejectsBadLiteralsAndNumbers() {
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("-"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("1."));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("\"\\u12\""));
    }

    @Test
    void limitsDepthAndNodes() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("[");
        }
        for (int i = 0; i < 40; i++) {
            deep.append("]");
        }
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(deep.toString()));

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 1500; i++) {
            many.append(i).append(i < 1499 ? "," : "");
        }
        many.append("]");
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse(many.toString()));
        assertTrue(JsonReader.parse(many.toString(), 32, 5000) instanceof List<?>);

        // NaN/Infinity tokens are not valid JSON.
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("NaN"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> JsonReader.parse("-Infinity"));
    }

    @Test
    void byteParsingMatchesStringParsing() {
        assertEquals(Map.of("x", 9L), JsonReader.parse("{\"x\":9}".getBytes()));
    }
}
