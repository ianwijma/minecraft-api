package dev.example.mapi.internal.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.example.mapi.internal.job.JobContext;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TickStepWaiterTest {

    @Test
    void waitsForFrozenStepCounterEvenWhileServerTickCountMoves() throws Exception {
        List<TickControlBackend.State> states = List.of(
                state(110, 3), state(120, 2), state(121, 0));
        AtomicInteger reads = new AtomicInteger();

        var result = TickStepWaiter.await(new TickControlBackend.StepResult(3, 0, 100),
                new TestContext(), () -> states.get(reads.getAndIncrement()));

        assertEquals(3, result.completed());
        assertEquals(121, result.boundaryTickCount());
        assertEquals(3, reads.get());
    }

    @Test
    void cancelledWaitDoesNotReportPartialStepAsCompleted() {
        AtomicInteger reads = new AtomicInteger();
        assertThrows(CancellationException.class, () -> TickStepWaiter.await(
                new TickControlBackend.StepResult(4, 0, 100), new TestContext(true), () -> {
                    reads.incrementAndGet();
                    return state(104, 0);
                }));
        assertEquals(0, reads.get());
    }

    @Test
    void requiresServerToStayFrozenWhileStepIsPending() {
        ProblemException failure = assertThrows(ProblemException.class, () -> TickStepWaiter.await(
                new TickControlBackend.StepResult(4, 0, 100), new TestContext(),
                () -> new TickControlBackend.State(false, false, 20, 104, Optional.empty(), 0)));
        assertEquals(ProblemCode.SERVER_PAUSED, failure.code());
    }

    private static TickControlBackend.State state(long serverTick, int remaining) {
        return new TickControlBackend.State(true, false, 20, serverTick, Optional.empty(), remaining);
    }

    private static final class TestContext implements JobContext {

        private final boolean cancelled;

        TestContext() {
            this(false);
        }

        TestContext(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public String jobId() {
            return "test";
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public long remainingWallMs() {
            return 10_000;
        }

        @Override
        public void checkCancelled() {
            if (cancelled) {
                throw new CancellationException();
            }
        }

        @Override
        public void checkDeadline() {
        }

        @Override
        public void milestone(String name, Map<String, Object> details) {
        }
    }
}
