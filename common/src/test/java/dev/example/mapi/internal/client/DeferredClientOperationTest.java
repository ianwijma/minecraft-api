package dev.example.mapi.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DeferredClientOperationTest {

    @Test
    void preparationIsSynchronousButInitializationIsOnlyQueued() {
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicBoolean prepared = new AtomicBoolean();
        AtomicBoolean initialized = new AtomicBoolean();

        DeferredClientOperation.enqueue(() -> {
            prepared.set(true);
            return () -> initialized.set(true);
        }, queue::add, failure -> {
            throw new AssertionError(failure);
        });

        assertTrue(prepared.get());
        assertFalse(initialized.get());
        assertEquals(1, queue.size());
        queue.remove().run();
        assertTrue(initialized.get());
    }

    @Test
    void preparationFailureRejectsBeforeQueueAdmission() {
        Queue<Runnable> queue = new ArrayDeque<>();
        ProblemException expected = new ProblemException(ProblemCode.BAD_REQUEST, "invalid id");

        ProblemException actual = assertThrows(ProblemException.class,
                () -> DeferredClientOperation.enqueue(() -> {
                    throw expected;
                }, queue::add, failure -> {}));

        assertSame(expected, actual);
        assertTrue(queue.isEmpty());
    }

    @Test
    void queuedFailureIsDeliveredToObservableFailureHandler() {
        Queue<Runnable> queue = new ArrayDeque<>();
        RuntimeException expected = new IllegalStateException("native initialization failed");
        AtomicReference<RuntimeException> observed = new AtomicReference<>();

        DeferredClientOperation.enqueue(() -> () -> {
            throw expected;
        }, queue::add, observed::set);
        queue.remove().run();

        assertSame(expected, observed.get());
    }

    @Test
    void schedulerRejectionIsObservedAndPropagated() {
        RuntimeException expected = new IllegalStateException("client scheduler stopped");
        AtomicReference<RuntimeException> observed = new AtomicReference<>();

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> DeferredClientOperation.enqueue(() -> () -> {}, task -> {
                    throw expected;
                }, observed::set));

        assertSame(expected, actual);
        assertSame(expected, observed.get());
    }
}
