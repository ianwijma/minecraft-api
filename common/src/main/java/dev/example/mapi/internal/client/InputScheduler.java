package dev.example.mapi.internal.client;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.function.LongSupplier;

/**
 * Input scheduling semantics (spec §4.2): a hold of N client ticks dispatches
 * key-down before the selected input-processing boundary, keeps the synthetic
 * held state for N qualifying client ticks, dispatches key-up before the
 * following boundary, and reports the boundaries actually observed. A press
 * never collapses into an interval in which the consumer cannot observe it.
 *
 * <p>Waits run on a monotonic wall clock with a hard deadline (spec §4.3);
 * on deadline or interruption the key-up is always dispatched first
 * (release-all) and then the failure is reported — held input never leaks.
 */
public final class InputScheduler {

    /** Outcome of a bounded hold. */
    public record HoldResult(int keyCode, int requestedTicks, int heldTicks,
            long startBoundary, long endBoundary) {
    }

    /** Dispatches key-down/key-up (implemented by the input backend caller). */
    public interface Dispatch {
        void down(int keyCode);

        void up(int keyCode);
    }

    private final LongSupplier clientTick;
    private final LongSupplier epochMillis;
    private final LongSupplier monotonicNanos;
    private final InterruptibleWait waitBoundary;

    @FunctionalInterface
    interface InterruptibleWait {
        void await() throws InterruptedException;
    }

    /**
     * @param clientTick the client-tick boundary counter (spec §4.1), never
     *                   {@code null}
     */
    public InputScheduler(LongSupplier clientTick) {
        this(clientTick, System::currentTimeMillis, System::nanoTime, () -> Thread.sleep(5));
    }

    InputScheduler(LongSupplier clientTick, LongSupplier epochMillis,
            LongSupplier monotonicNanos, InterruptibleWait waitBoundary) {
        this.clientTick = java.util.Objects.requireNonNull(clientTick, "clientTick");
        this.epochMillis = java.util.Objects.requireNonNull(epochMillis, "epochMillis");
        this.monotonicNanos = java.util.Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.waitBoundary = java.util.Objects.requireNonNull(waitBoundary, "waitBoundary");
    }

    /**
     * Holds a key for a bounded number of client ticks.
     *
     * @param dispatch         key dispatch callbacks
     * @param keyCode          key to hold
     * @param ticks            requested hold length, 1..3600
     * @param deadlineEpochMs  wall-clock deadline
     * @return the observed boundaries and held count
     * @throws ProblemException with {@code DEADLINE_EXCEEDED} when the
     *     deadline elapsed before the hold completed (key released first)
     */
    public HoldResult holdKey(Dispatch dispatch, int keyCode, int ticks, long deadlineEpochMs)
            throws InterruptedException {
        return holdKey(dispatch, keyCode, ticks, deadlineEpochMs, () -> {});
    }

    /**
     * Holds a key while control ownership remains valid.
     *
     * @param dispatch key dispatch callbacks
     * @param keyCode key to hold
     * @param ticks requested hold length, 1..3600
     * @param deadlineEpochMs wall-clock deadline
     * @param requireControl ownership check before dispatch and throughout the hold
     * @return the observed boundaries and held count
     * @throws InterruptedException when interrupted, after releasing the key
     */
    public HoldResult holdKey(Dispatch dispatch, int keyCode, int ticks, long deadlineEpochMs,
            Runnable requireControl) throws InterruptedException {
        if (ticks < 1 || ticks > 3600) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 3600");
        }
        java.util.Objects.requireNonNull(requireControl, "requireControl");
        long remainingMs = Math.max(0, deadlineEpochMs - epochMillis.getAsLong());
        long budgetNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(remainingMs);
        long startedNanos = monotonicNanos.getAsLong();
        requireControl.run();
        if (remainingMs == 0) {
            throw new ProblemException(ProblemCode.DEADLINE_EXCEEDED,
                    "deadline elapsed before input dispatch");
        }
        try {
            dispatch.down(keyCode);
            long startBoundary = clientTick.getAsLong();
            int held = 0;
            long last = startBoundary;
            while (held < ticks) {
                requireControl.run();
                long now = clientTick.getAsLong();
                if (now != last) {
                    held++;
                    last = now;
                }
                if (monotonicNanos.getAsLong() - startedNanos >= budgetNanos) {
                    throw new ProblemException(ProblemCode.DEADLINE_EXCEEDED,
                            "client ticks did not advance within the deadline",
                            java.util.Map.of("heldTicks", held, "requestedTicks", ticks));
                }
                waitBoundary.await();
            }
            return new HoldResult(keyCode, ticks, held, startBoundary, clientTick.getAsLong());
        } finally {
            dispatch.up(keyCode);
        }
    }
}
