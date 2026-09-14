package dev.example.mapi.internal.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.events.EventLog.EventRecord;
import dev.example.mapi.internal.events.EventLog.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventLogTest {

    @Test
    void sequenceIsMonotonicAndCarriesIdentity() {
        EventLog log = new EventLog("proc-1", () -> "world-1");
        EventRecord first = log.publish("a.one", "instrumented", Map.of("k", 1));
        EventRecord second = log.publish("a.two", "api-originated", Map.of());
        assertEquals(1, first.seq());
        assertEquals(2, second.seq());
        assertEquals("proc-1", first.processSessionId());
        assertEquals("world-1", first.worldSessionId());
        assertEquals(2, log.headSeq());
        assertEquals(1, log.oldestSeq());
    }

    @Test
    void invalidSourceIsRejected() {
        EventLog log = new EventLog("proc-1", () -> null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> log.publish("a.b", "not-a-source", Map.of()));
    }

    @Test
    void eventsAfterReturnsOrderedPage() {
        EventLog log = new EventLog("proc-1", () -> null);
        for (int i = 0; i < 10; i++) {
            log.publish("t." + i, "instrumented", Map.of("i", i));
        }
        List<EventRecord> after3 = log.eventsAfter(3);
        assertEquals(7, after3.size());
        assertEquals(4, after3.getFirst().seq());
        assertEquals(10, after3.getLast().seq());
    }

    @Test
    void capacityEvictionMovesOldestForward() {
        EventLog log = new EventLog("proc-1", () -> null);
        for (int i = 0; i < EventLog.CAPACITY + 50; i++) {
            log.publish("t", "instrumented", Map.of("i", i));
        }
        assertEquals(EventLog.CAPACITY, log.eventsAfter(0).size());
        assertEquals(51, log.oldestSeq());
        assertEquals(EventLog.CAPACITY + 50, log.headSeq());
    }

    @Test
    void subscribeAfterReplaysWithoutGapsOrDuplicates() {
        EventLog log = new EventLog("proc-1", () -> null);
        log.publish("t.1", "instrumented", Map.of());
        log.publish("t.2", "instrumented", Map.of());
        List<Long> seen = new ArrayList<>();
        Subscription subscription = log.subscribeAfter(1, event -> seen.add(event.seq()));
        log.publish("t.3", "instrumented", Map.of());
        subscription.unsubscribe();
        log.publish("t.4", "instrumented", Map.of());
        assertEquals(List.of(2L, 3L), seen);
    }
}
