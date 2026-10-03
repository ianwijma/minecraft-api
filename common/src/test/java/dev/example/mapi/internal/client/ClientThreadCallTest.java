package dev.example.mapi.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.connection.ConnectionPolicy;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ClientThreadCallTest {

    @Test
    void timeoutCancelsQueuedSupplierBeforeItCanMutate() {
        BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        AtomicBoolean mutated = new AtomicBoolean();

        ProblemException failure = assertThrows(ProblemException.class,
                () -> ClientThreadCall.call(() -> false, queued::add, () -> {
                    mutated.set(true);
                    return "done";
                }, 20));

        assertEquals(ProblemCode.SERVER_BUSY, failure.code());
        queued.remove().run();
        assertFalse(mutated.get());
    }

    @Test
    void cleanupTimeoutLeavesQueuedSupplierRunnable() {
        BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        AtomicBoolean cleanedUp = new AtomicBoolean();

        ProblemException failure = assertThrows(ProblemException.class,
                () -> ClientThreadCall.callCleanup(() -> false, queued::add, () -> {
                    cleanedUp.set(true);
                    return "released";
                }, 20));

        assertEquals(ProblemCode.SERVER_BUSY, failure.code());
        assertTrue(failure.getMessage().contains("cleanup remains scheduled"));
        assertFalse(cleanedUp.get());
        queued.remove().run();
        assertTrue(cleanedUp.get());
    }

    @Test
    void interruptionCancelsQueuedSupplierAndRestoresInterruptFlag() throws Exception {
        BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        AtomicBoolean mutated = new AtomicBoolean();
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<ProblemException> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                ClientThreadCall.call(() -> false, queued::add, () -> {
                    mutated.set(true);
                    return "done";
                });
            } catch (ProblemException e) {
                failure.set(e);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        assertTrue(waitUntilQueued(queued));
        caller.interrupt();
        caller.join(2_000);

        assertFalse(caller.isAlive());
        assertEquals(ProblemCode.SERVER_BUSY, failure.get().code());
        assertTrue(interrupted.get());
        queued.remove().run();
        assertFalse(mutated.get());
    }

    @Test
    void executesInlineAndThroughQueuedPathAndPropagatesProblemException() {
        assertEquals("inline", ClientThreadCall.call(() -> true,
                runnable -> { throw new AssertionError("inline work must not enqueue"); }, () -> "inline"));
        assertEquals("queued", ClientThreadCall.call(() -> false, Runnable::run, () -> "queued"));

        ProblemException expected = new ProblemException(ProblemCode.LEASE_REQUIRED, "lease missing");
        ProblemException actual = assertThrows(ProblemException.class,
                () -> ClientThreadCall.call(() -> false, Runnable::run, () -> {
                    throw expected;
                }));
        assertSame(expected, actual);
    }

    @Test
    void queuedWorkCarriesApiAuthorizationAndRestoresClientContext() throws Exception {
        BlockingQueue<Runnable> queued = new LinkedBlockingQueue<>();
        var apiContext = ConnectionPolicy.captureContext(java.util.Set.of(Scope.CLIENT_CONNECT),
                java.util.List.of("localhost", "127.0.0.1:25565"));
        var unrelatedContext = ConnectionPolicy.captureContext(java.util.Set.of(), java.util.List.of());
        AtomicReference<ConnectionPolicy.RequestContext> observed = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                ConnectionPolicy.withContext(apiContext, () -> ClientThreadCall.call(
                        () -> false, queued::add, () -> {
                            observed.set(ConnectionPolicy.currentContext());
                            return null;
                        }));
                assertNull(ConnectionPolicy.currentContext());
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        caller.start();
        Runnable dispatched = queued.poll(2, TimeUnit.SECONDS);
        assertTrue(dispatched != null);
        ConnectionPolicy.withContext(unrelatedContext, () -> {
            dispatched.run();
            assertSame(unrelatedContext, ConnectionPolicy.currentContext());
        });
        caller.join(2_000);
        assertFalse(caller.isAlive());
        assertNull(failure.get());
        assertSame(apiContext, observed.get());
        assertNull(ConnectionPolicy.currentContext());
    }

    @Test
    void manualQueuedWorkDoesNotInheritAnUnrelatedApiContext() {
        var unrelatedContext = ConnectionPolicy.captureContext(java.util.Set.of(), java.util.List.of());
        ClientThreadCall.call(() -> false,
                runnable -> ConnectionPolicy.withContext(unrelatedContext, runnable),
                () -> {
                    assertNull(ConnectionPolicy.currentContext());
                    return null;
                });
        assertNull(ConnectionPolicy.currentContext());
    }

    private static boolean waitUntilQueued(BlockingQueue<Runnable> queue) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < until) {
            if (!queue.isEmpty()) {
                return true;
            }
            Thread.sleep(5);
        }
        return !queue.isEmpty();
    }
}
