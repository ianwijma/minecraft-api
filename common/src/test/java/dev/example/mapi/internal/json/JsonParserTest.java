package dev.example.mapi.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.json.JsonParser.ParseException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonParserTest {

    @Test
    void parsesScalars() {
        assertEquals("hello", JsonParser.parse("\"hello\""));
        assertEquals(Boolean.TRUE, JsonParser.parse("true"));
        assertEquals(Boolean.FALSE, JsonParser.parse("false"));
        assertNullValue(JsonParser.parse("null"));
        assertEquals(42L, JsonParser.parse("42"));
        assertEquals(-7L, JsonParser.parse("-7"));
        assertEquals(1.5d, JsonParser.parse("1.5"));
        assertEquals(1.0e3d, JsonParser.parse("1e3"));
    }

    private static void assertNullValue(Object value) {
        assertEquals(null, value);
    }

    @Test
    void parsesNestedStructuresWithOrder() {
        Object value = JsonParser.parse("""
                {"b":1,"a":[true,null,"x\\ny",{"deep":[-0.5]}],"n":123456789012345}
                """);
        Map<?, ?> map = assertInstanceOf(Map.class, value);
        assertEquals(1L, map.get("b"));
        assertEquals(123456789012345L, map.get("n"));
        List<?> list = assertInstanceOf(List.class, map.get("a"));
        assertEquals(Boolean.TRUE, list.get(0));
        assertEquals(null, list.get(1));
        assertEquals("x\ny", list.get(2));
        Map<?, ?> deep = assertInstanceOf(Map.class, list.get(3));
        List<?> deepList = assertInstanceOf(List.class, deep.get("deep"));
        assertEquals(-0.5d, deepList.get(0));
    }

    @Test
    void unicodeAndEscapes() {
        assertEquals("quote\" back\\ slash/ \b\f\n\r\t A",
                JsonParser.parse("\"quote\\\" back\\\\ slash/ \\b\\f\\n\\r\\t \\u0041\""));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(ParseException.class, () -> JsonParser.parse("{"));
        assertThrows(ParseException.class, () -> JsonParser.parse("[1,]"));
        assertThrows(ParseException.class, () -> JsonParser.parse("{\"a\":}"));
        assertThrows(ParseException.class, () -> JsonParser.parse("{} trailing"));
        assertThrows(ParseException.class, () -> JsonParser.parse("\"unterminated"));
        assertThrows(ParseException.class, () -> JsonParser.parse("nul"));
        assertThrows(ParseException.class, () -> JsonParser.parse("+1"));
        assertThrows(ParseException.class, () -> JsonParser.parse("'single'"));
        assertThrows(ParseException.class, () -> JsonParser.parse("{\"a\" 1}"));
        assertThrows(ParseException.class, () -> JsonParser.parse("01x"));
    }

    @Test
    void rejectsOversizedAndTooDeepInput() {
        assertThrows(ParseException.class, () -> JsonParser.parse(" ".repeat(JsonParser.MAX_INPUT_CHARS + 1)));
        String deep = "[".repeat(JsonParser.MAX_DEPTH + 2) + "]".repeat(JsonParser.MAX_DEPTH + 2);
        assertThrows(ParseException.class, () -> JsonParser.parse(deep));
        String shallow = "[".repeat(JsonParser.MAX_DEPTH) + "]".repeat(JsonParser.MAX_DEPTH);
        assertTrue(JsonParser.parse(shallow) instanceof List<?>);
    }

    @Test
    void controlCharactersInStringsAreRejected() {
        assertThrows(ParseException.class, () -> JsonParser.parse("\"a\nb\""));
    }

    @Test
    void duplicateKeysLastWins() {
        Map<?, ?> map = assertInstanceOf(Map.class, JsonParser.parse("{\"a\":1,\"a\":2}"));
        assertEquals(2L, map.get("a"));
    }
}
