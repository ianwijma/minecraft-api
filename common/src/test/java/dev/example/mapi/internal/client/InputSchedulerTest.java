package dev.example.mapi.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InputSchedulerTest {

    /** Fake client-tick clock advancing on demand. */
    private static final class FakeClock {
        private final AtomicLong tick = new AtomicLong(100);

        long get() {
            return tick.get();
        }

        void advance() {
            tick.incrementAndGet();
        }
    }

    private static InputScheduler.Dispatch recording(AtomicInteger downs,
            AtomicInteger ups) {
        return new InputScheduler.Dispatch() {
            @Override
            public void down(int keyCode) {
                downs.incrementAndGet();
            }

            @Override
            public void up(int keyCode) {
                ups.incrementAndGet();
            }
        };
    }

    @Test
    void holdDispatchesDownHoldsThenUpWithObservedBoundaries() throws Exception {
        FakeClock clock = new FakeClock();
        InputScheduler scheduler = new InputScheduler(clock::get);
        AtomicInteger downs = new AtomicInteger();
        AtomicInteger ups = new AtomicInteger();

        // Advance the tick from a helper thread while the hold runs.
        Thread ticker = new Thread(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(20);
                    clock.advance();
                }
            } catch (InterruptedException ignored) {
                // test teardown
            }
        });
        ticker.start();
        InputScheduler.HoldResult result =
                scheduler.holdKey(recording(downs, ups), 66, 3, System.currentTimeMillis() + 5000);
        ticker.join();

        assertEquals(1, downs.get());
        assertEquals(1, ups.get());
        assertEquals(3, result.heldTicks());
        assertEquals(3, result.requestedTicks());
        assertEquals(66, result.keyCode());
        assertTrue(result.endBoundary() > result.startBoundary());
        // Key-up happens at or after the boundary following the hold.
        assertTrue(result.endBoundary() >= result.startBoundary() + 3);
    }

    @Test
    void deadlineReleasesTheKeyBeforeFailing() {
        FakeClock clock = new FakeClock(); // never advances
        InputScheduler scheduler = new InputScheduler(clock::get);
        AtomicInteger downs = new AtomicInteger();
        AtomicInteger ups = new AtomicInteger();

        ProblemException e = assertThrows(ProblemException.class, () -> scheduler.holdKey(
                recording(downs, ups), 66, 3, System.currentTimeMillis() + 200));
        assertEquals(ProblemCode.DEADLINE_EXCEEDED, e.code());
        assertEquals(1, downs.get(), "key-down dispatched");
        assertEquals(1, ups.get(), "key-up released before the failure surfaced");
        assertEquals(0, ((Number) e.details().get("heldTicks")).intValue());
    }

    @Test
    void invalidBoundsAreRejectedWithoutDispatching() {
        FakeClock clock = new FakeClock();
        InputScheduler scheduler = new InputScheduler(clock::get);
        AtomicInteger downs = new AtomicInteger();
        AtomicInteger ups = new AtomicInteger();
        assertThrows(ProblemException.class,
                () -> scheduler.holdKey(recording(downs, ups), 66, 0, Long.MAX_VALUE));
        assertThrows(ProblemException.class,
                () -> scheduler.holdKey(recording(downs, ups), 66, 3601, Long.MAX_VALUE));
        assertEquals(0, downs.get());
        assertEquals(0, ups.get());
    }

    @Test
    void heldTicksNeverExceedRequestWhenClockStallsMidway() throws Exception {
        FakeClock clock = new FakeClock();
        InputScheduler scheduler = new InputScheduler(clock::get);
        AtomicInteger downs = new AtomicInteger();
        AtomicInteger ups = new AtomicInteger();
        Thread ticker = new Thread(() -> {
            try {
                Thread.sleep(30);
                clock.advance();
                Thread.sleep(40);
                clock.advance();
                // no further advances: stall after 2 of the requested 5 ticks
            } catch (InterruptedException ignored) {
                // test teardown
            }
        });
        ticker.start();
        ProblemException e = assertThrows(ProblemException.class, () -> scheduler.holdKey(
                recording(downs, ups), 66, 5, System.currentTimeMillis() + 400));
        ticker.join();
        assertEquals(ProblemCode.DEADLINE_EXCEEDED, e.code());
        assertEquals(2, ((Number) e.details().get("heldTicks")).intValue());
        assertEquals(1, ups.get(), "release-all on deadline");
    }
}
