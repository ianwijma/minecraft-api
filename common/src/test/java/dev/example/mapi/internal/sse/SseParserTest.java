package dev.example.mapi.internal.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    private static List<SseParser.SseEvent> feed(SseParser parser, String chunk) {
        return parser.feed(chunk.getBytes(StandardCharsets.UTF_8), 0, chunk.length());
    }

    @Test
    void parsesCompleteFrames() {
        SseParser parser = new SseParser();
        List<SseParser.SseEvent> events = feed(parser, "id: 7\nevent: tick.step\ndata: {\"a\":1}\n\n");
        assertEquals(1, events.size());
        assertEquals("7", events.get(0).id());
        assertEquals("tick.step", events.get(0).event());
        assertEquals("{\"a\":1}", events.get(0).data());
    }

    @Test
    void handlesArbitraryChunkBoundaries() {
        SseParser parser = new SseParser();
        assertTrue(feed(parser, "id: 1\nev").isEmpty());
        assertTrue(feed(parser, "ent: hello\nda").isEmpty());
        List<SseParser.SseEvent> events = feed(parser, "ta: one\ndata: two\n\n");
        assertEquals(1, events.size());
        assertEquals("hello", events.get(0).event());
        assertEquals("one\ntwo", events.get(0).data());
        assertEquals("1", events.get(0).id());
    }

    @Test
    void skipsCommentsAndKeepsState() {
        SseParser parser = new SseParser();
        assertTrue(feed(parser, ": keepalive 123\n\n: event-gap droppedUpTo=4\n\ndata: x\n\n").size() == 1);
        List<SseParser.SseEvent> events = feed(parser, "data: y\n\n");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).event());
        assertEquals("y", events.get(0).data());
    }

    @Test
    void handlesCrlfAndBareCr() {
        SseParser parser = new SseParser();
        List<SseParser.SseEvent> events = feed(parser, "id: 3\r\ndata: a\r\ndata: b\r\n\r\n");
        assertEquals(1, events.size());
        assertEquals("a\nb", events.get(0).data());
        List<SseParser.SseEvent> crOnly = feed(parser, "id: 4\rdata: c\r\r");
        assertEquals(1, crOnly.size());
        assertEquals("4", crOnly.get(0).id());
    }

    @Test
    void multiLineDataJoinsWithNewline() {
        SseParser parser = new SseParser();
        List<SseParser.SseEvent> events = feed(parser, "data: line1\ndata: line2\n\n");
        assertEquals(1, events.size());
        assertEquals("line1\nline2", events.get(0).data());
    }

    @Test
    void emptyDataAndDefaults() {
        SseParser parser = new SseParser();
        List<SseParser.SseEvent> events = feed(parser, "data:\n\n");
        assertEquals(1, events.size());
        assertEquals("", events.get(0).data());
        assertEquals("", events.get(0).id());
        assertEquals("message", events.get(0).event());
    }
}
