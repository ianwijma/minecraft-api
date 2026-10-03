package dev.example.mapi.internal.tick;

import dev.example.mapi.internal.job.JobContext;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.Objects;
import java.util.function.Supplier;

/** Waits off-thread for vanilla's scheduled frozen-step counter to drain. */
public final class TickStepWaiter {

    private static final long POLL_INTERVAL_MS = 25;

    private TickStepWaiter() {
    }

    /**
     * Polls state snapshots until vanilla reports no frozen ticks remain.
     * The caller must invoke this from a job worker, not the server thread.
     *
     * @param scheduled the step request and its starting tick boundary
     * @param context owning job cancellation/deadline context
     * @param state supplier that dispatches a fresh state read to the server thread
     * @return a result whose completed count reflects the drained step request
     * @throws InterruptedException if the job worker is interrupted
     */
    public static TickControlBackend.StepResult await(TickControlBackend.StepResult scheduled,
            JobContext context, Supplier<TickControlBackend.State> state) throws InterruptedException {
        Objects.requireNonNull(scheduled, "scheduled");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(state, "state");
        if (scheduled.completed() >= scheduled.requested()) {
            return scheduled;
        }

        while (true) {
            context.checkCancelled();
            context.checkDeadline();
            TickControlBackend.State current = state.get();
            if (!current.frozen()) {
                throw new ProblemException(ProblemCode.SERVER_PAUSED,
                        "server was unfrozen before the requested tick step completed");
            }
            int remaining = current.frozenTicksToRun();
            if (remaining == 0) {
                return new TickControlBackend.StepResult(scheduled.requested(), scheduled.requested(),
                        current.tickCount());
            }
            if (remaining < 0 || remaining > scheduled.requested()) {
                throw new IllegalStateException("vanilla reported an invalid frozen step count: " + remaining);
            }
            long remainingWallMs = context.remainingWallMs();
            if (remainingWallMs <= 0) {
                context.checkDeadline();
            }
            Thread.sleep(Math.min(POLL_INTERVAL_MS, Math.max(1, remainingWallMs)));
        }
    }
}
