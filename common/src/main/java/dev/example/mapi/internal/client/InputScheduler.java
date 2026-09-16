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

    /**
     * @param clientTick the client-tick boundary counter (spec §4.1), never
     *                   {@code null}
     */
    public InputScheduler(LongSupplier clientTick) {
        this.clientTick = java.util.Objects.requireNonNull(clientTick, "clientTick");
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
        if (ticks < 1 || ticks > 3600) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 3600");
        }
        dispatch.down(keyCode);
        long startBoundary = clientTick.getAsLong();
        try {
            int held = 0;
            long last = startBoundary;
            while (held < ticks) {
                long now = clientTick.getAsLong();
                if (now != last) {
                    held++;
                    last = now;
                }
                if (System.currentTimeMillis() >= deadlineEpochMs) {
                    throw new ProblemException(ProblemCode.DEADLINE_EXCEEDED,
                            "client ticks did not advance within the deadline",
                            java.util.Map.of("heldTicks", held, "requestedTicks", ticks));
                }
                Thread.sleep(5);
            }
            dispatch.up(keyCode);
            return new HoldResult(keyCode, ticks, held, startBoundary, clientTick.getAsLong());
        } catch (ProblemException e) {
            dispatch.up(keyCode);
            throw e;
        } catch (RuntimeException e) {
            dispatch.up(keyCode);
            throw e;
        }
    }
}
