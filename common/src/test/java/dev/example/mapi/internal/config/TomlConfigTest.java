package dev.example.mapi.internal.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomlConfigTest {

    @TempDir
    Path dir;

    @Test
    void parsesSectionsAndScalarsIntoDottedKeys() {
        List<TomlConfig.Entry> entries = TomlConfig.parse("""
                # header comment
                [http]
                enabled = true
                port = 25586
                name = "client-1"
                """);
        assertEquals(3, entries.size());
        assertEquals("http.enabled", entries.get(0).key());
        assertEquals(Boolean.TRUE, entries.get(0).value());
        assertEquals("http.port", entries.get(1).key());
        assertEquals(25586L, entries.get(1).value());
        assertEquals("http.name", entries.get(2).key());
        assertEquals("client-1", entries.get(2).value());
    }

    @Test
    void rootLevelKeysAndInlineComments() {
        List<TomlConfig.Entry> entries = TomlConfig.parse("""
                standalone = "yes" # trailing comment
                count = -3
                """);
        assertEquals("standalone", entries.getFirst().key());
        assertEquals("yes", entries.getFirst().value());
        assertEquals(-3L, entries.get(1).value());
    }

    @Test
    void stringArraysParse() {
        List<TomlConfig.Entry> entries = TomlConfig.parse("items = [\"a\", \"b\"]");
        assertEquals(List.of("a", "b"), entries.getFirst().value());
    }

    @Test
    void rejectsUnknownSyntax() {
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("key = 'single'"));
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("key = 1.5"));
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("= 3"));
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("[unclosed"));
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("key = \"a\\nb\""));
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("key = [1, 2]"));
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.parse("[http\nx = 1"));
    }

    @Test
    void writeRoundTripKeepsOrderAndComments() throws IOException {
        Path file = dir.resolve("out.toml");
        List<TomlConfig.Entry> entries = List.of(
                new TomlConfig.Entry("http.enabled", false),
                new TomlConfig.Entry("http.port", 25586),
                new TomlConfig.Entry("http.scopes", ""),
                new TomlConfig.Entry("reflection.enabled", false));
        TomlConfig.write(entries, Map.of("http.port", "Loopback port to bind."), file);
        String text = Files.readString(file);
        assertTrue(text.contains("[http]"));
        assertTrue(text.contains("# Try up to N") || text.contains("Loopback port"), text);
        assertTrue(text.contains("port = 25586"));

        List<TomlConfig.Entry> back = TomlConfig.parse(text);
        assertEquals(4, back.size());
        assertEquals("http.enabled", back.getFirst().key());
        assertEquals(Boolean.FALSE, back.getFirst().value());
    }

    @Test
    void writeRefusesUnsafeStrings() {
        assertThrows(TomlConfig.TomlException.class, () -> TomlConfig.write(
                List.of(new TomlConfig.Entry("http.token", "with\"quote")), null, dir.resolve("x.toml")));
    }
}
