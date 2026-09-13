package dev.example.mapi.internal.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    void writesPrimitives() {
        assertEquals("null", JsonWriter.write(null));
        assertEquals("true", JsonWriter.write(Boolean.TRUE));
        assertEquals("false", JsonWriter.write(Boolean.FALSE));
        assertEquals("42", JsonWriter.write(42));
        assertEquals("9007199254740993", JsonWriter.write(9007199254740993L));
        assertEquals("1.5", JsonWriter.write(1.5d));
        assertEquals("2.5", JsonWriter.write(2.5f));
    }

    @Test
    void escapesStrings() {
        assertEquals("\"\"", JsonWriter.write(""));
        assertEquals("\"plain\"", JsonWriter.write("plain"));
        assertEquals("\"a\\\"b\"", JsonWriter.write("a\"b"));
        assertEquals("\"a\\\\b\"", JsonWriter.write("a\\b"));
        assertEquals("\"\\n\\t\\r\\b\\f\"", JsonWriter.write("\n\t\r\b\f"));
        assertEquals("\"\\u0001\\u001f\"", JsonWriter.write("\u0001\u001f"));
        assertEquals("\"unicode-é漢\"", JsonWriter.write("unicode-é漢"));
    }

    @Test
    void writesNestedStructuresInIterationOrder() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", 1);
        inner.put("a", "two");
        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("name", "mapi");
        outer.put("inner", inner);
        outer.put("list", java.util.Arrays.asList(1, "x", null));
        assertEquals("{\"name\":\"mapi\",\"inner\":{\"z\":1,\"a\":\"two\"},\"list\":[1,\"x\",null]}",
                JsonWriter.write(outer));
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(Float.NEGATIVE_INFINITY));
        Map<Object, Object> badKey = new LinkedHashMap<>();
        badKey.put(42, "nope");
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(badKey));
        assertThrows(IllegalArgumentException.class, () -> JsonWriter.write(new Object()));
    }
}
