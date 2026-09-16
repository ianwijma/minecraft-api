package dev.example.mapi.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EventBusTest {

    @Test
    void sequenceNumbersAreMonotonicAndOrdered() {
        EventBus bus = new EventBus();
        assertEquals(0, bus.latestSeq());
        Event a = bus.publish("a", Optional.empty(), Map.of());
        Event b = bus.publish("b", Optional.empty(), null);
        assertEquals(1, a.seq());
        assertEquals(2, b.seq());
        assertEquals(2, bus.latestSeq());
        assertEquals(Map.of(), b.payload());
    }

    @Test
    void cursorPollReturnsMatchingEventsAndAdvances() {
        EventBus bus = new EventBus();
        bus.publish("world.loaded", Optional.of("w1"), Map.of());
        bus.publish("world.unloaded", Optional.of("w1"), Map.of());
        bus.publish("world.loaded", Optional.of("w2"), Map.of());

        EventFilter unloaded = new EventFilter(Set.of("world.unloaded"), Optional.empty());
        EventBus.EventsAfter result = bus.eventsAfter(0, unloaded, 100);
        assertEquals(List.of(2L), result.events().stream().map(Event::seq).toList());
        assertEquals(2, result.newCursor());

        EventBus.EventsAfter empty = bus.eventsAfter(2, unloaded, 100);
        assertTrue(empty.events().isEmpty());
        assertEquals(2, empty.newCursor());
        assertFalse(empty.gap());
    }

    @Test
    void worldFilterRestrictsToWorld() {
        EventBus bus = new EventBus();
        bus.publish("tick.step", Optional.of("w1"), Map.of());
        bus.publish("tick.step", Optional.of("w2"), Map.of());

        EventFilter w2 = new EventFilter(Set.of("tick.step"), Optional.of("w2"));
        EventBus.EventsAfter result = bus.eventsAfter(0, w2, 100);
        assertEquals(1, result.events().size());
        assertEquals("w2", result.events().get(0).worldSessionId().orElseThrow());
    }

    @Test
    void retentionDropsOldestAndReportsExplicitGaps() {
        EventBus bus = new EventBus(3);
        for (int i = 0; i < 5; i++) {
            bus.publish("tick.step", Optional.empty(), Map.of("i", i));
        }
        assertEquals(5, bus.latestSeq());
        assertEquals(2, bus.droppedUpToSeq());

        EventBus.EventsAfter result = bus.eventsAfter(0, EventFilter.any(), 100);
        assertFalse(result.gap(), "cursor 0 is before the stream start, not a gap");
        assertEquals(3, result.events().size());

        EventBus.EventsAfter gapped = bus.eventsAfter(1, EventFilter.any(), 100);
        assertTrue(gapped.gap());
        assertEquals(5, gapped.newCursor(), "cursor advances to the last matched event");
    }

    @Test
    void waitForEventReturnsAlreadyPublishedMatchesImmediately() throws Exception {
        EventBus bus = new EventBus();
        Event event = bus.publish("action.completed", Optional.empty(), Map.of());
        long cursor = bus.latestSeq() - 1;
        Optional<Event> found = bus.waitForEvent(EventFilter.any(), cursor, 100);
        assertTrue(found.isPresent());
        assertEquals(event.seq(), found.get().seq());
    }

    @Test
    void waitForEventTimesOutWithoutMatch() throws Exception {
        EventBus bus = new EventBus();
        long cursor = bus.latestSeq();
        assertTrue(bus.waitForEvent(new EventFilter(Set.of("never"), Optional.empty()),
                cursor, 50).isEmpty());
    }

    @Test
    void waitForEventWakesOnPublish() throws Exception {
        EventBus bus = new EventBus();
        long cursor = bus.latestSeq();
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicReference<Optional<Event>> received = new AtomicReference<>(Optional.empty());
        Thread waiter = new Thread(() -> {
            try {
                waiting.countDown();
                received.set(bus.waitForEvent(
                        new EventFilter(Set.of("x"), Optional.empty()), cursor, 5000));
            } catch (InterruptedException ignored) {
                // test teardown
            }
        });
        waiter.start();
        assertTrue(waiting.await(2, TimeUnit.SECONDS));
        Thread.sleep(50);
        bus.publish("x", Optional.empty(), Map.of("k", "v"));
        waiter.join(5000);
        assertTrue(received.get().isPresent());
        assertEquals("v", received.get().orElseThrow().payload().get("k"));
    }

    @Test
    void limitBoundsAndArgumentsAreValidated() {
        EventBus bus = new EventBus();
        assertThrows(IllegalArgumentException.class,
                () -> bus.eventsAfter(0, EventFilter.any(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> bus.eventsAfter(0, EventFilter.any(), 1001));
        assertThrows(NullPointerException.class, () -> bus.eventsAfter(0, null, 10));
        assertThrows(IllegalArgumentException.class,
                () -> bus.publish(" ", Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EventBus(0));
    }

    @Test
    void emptyTypesFilterMatchesAllTypesAndWorlds() {
        EventBus bus = new EventBus();
        bus.publish("a", Optional.of("w1"), Map.of());
        bus.publish("b", Optional.empty(), Map.of());
        EventFilter noConstraints = new EventFilter(Set.of(), Optional.empty());
        assertEquals(2, bus.eventsAfter(0, noConstraints, 100).events().size());
    }
}
