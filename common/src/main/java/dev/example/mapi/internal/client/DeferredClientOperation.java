package dev.example.mapi.internal.client;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Queues prepared client-thread work without waiting for its completion. */
public final class DeferredClientOperation {

    private DeferredClientOperation() {}

    /**
     * Prepares work synchronously, then queues it and reports runtime failures
     * through the supplied failure handler.
     *
     * @param prepare validates and constructs the queued operation
     * @param scheduler always-queue scheduler for the client thread
     * @param failureHandler observes failures raised after admission
     */
    public static void enqueue(Supplier<Runnable> prepare, Consumer<Runnable> scheduler,
            Consumer<RuntimeException> failureHandler) {
        Objects.requireNonNull(prepare, "prepare");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(failureHandler, "failureHandler");
        Runnable operation = Objects.requireNonNull(prepare.get(), "prepared operation");
        Runnable deferred = () -> {
            try {
                operation.run();
            } catch (RuntimeException failure) {
                failureHandler.accept(failure);
            }
        };
        try {
            scheduler.accept(deferred);
        } catch (RuntimeException failure) {
            failureHandler.accept(failure);
            throw failure;
        }
    }
}
