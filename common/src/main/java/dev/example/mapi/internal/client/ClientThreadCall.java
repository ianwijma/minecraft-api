package dev.example.mapi.internal.client;

import dev.example.mapi.internal.connection.ConnectionPolicy;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Bounded synchronous bridge for work that must run on the client thread. */
public final class ClientThreadCall {

    private static final long MAX_WAIT_MS = 5_000;

    private ClientThreadCall() {}

    /** Executes inline on the client thread, or dispatches and waits up to five seconds. */
    public static <T> T call(BooleanSupplier onClientThread, Consumer<Runnable> dispatch, Supplier<T> task) {
        return call(onClientThread, dispatch, task, MAX_WAIT_MS);
    }

    static <T> T call(BooleanSupplier onClientThread, Consumer<Runnable> dispatch,
            Supplier<T> task, long waitMs) {
        return call(onClientThread, dispatch, task, waitMs, true);
    }

    /** Executes cleanup with the same wait bound but leaves timed-out work queued. */
    public static <T> T callCleanup(BooleanSupplier onClientThread,
            Consumer<Runnable> dispatch, Supplier<T> task) {
        return call(onClientThread, dispatch, task, MAX_WAIT_MS, false);
    }

    static <T> T callCleanup(BooleanSupplier onClientThread, Consumer<Runnable> dispatch,
            Supplier<T> task, long waitMs) {
        return call(onClientThread, dispatch, task, waitMs, false);
    }

    private static <T> T call(BooleanSupplier onClientThread, Consumer<Runnable> dispatch,
            Supplier<T> task, long waitMs, boolean cancelWhenWaitEnds) {
        Objects.requireNonNull(onClientThread, "onClientThread");
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(task, "task");
        if (waitMs <= 0) {
            throw new IllegalArgumentException("waitMs must be positive");
        }
        if (onClientThread.getAsBoolean()) {
            return task.get();
        }

        var connectionContext = ConnectionPolicy.currentContext();
        FutureTask<T> future = new FutureTask<>(
                () -> ConnectionPolicy.withContext(connectionContext, task));
        dispatch.accept(future);
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (cancelWhenWaitEnds) {
                future.cancel(false);
            }
            String detail = cancelWhenWaitEnds
                    ? "work did not complete within " + waitMs + " ms"
                    : "cleanup remains scheduled after the caller's " + waitMs + " ms wait";
            throw new ProblemException(ProblemCode.SERVER_BUSY, "client thread busy; " + detail);
        } catch (InterruptedException e) {
            if (cancelWhenWaitEnds) {
                future.cancel(false);
            }
            Thread.currentThread().interrupt();
            String detail = cancelWhenWaitEnds ? "interrupted"
                    : "interrupted; client-thread cleanup remains scheduled";
            throw new ProblemException(ProblemCode.SERVER_BUSY, detail);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof ProblemException problem) {
                throw problem;
            }
            throw new ProblemException(ProblemCode.INTERNAL, "client-thread work failed: " + cause);
        }
    }
}
