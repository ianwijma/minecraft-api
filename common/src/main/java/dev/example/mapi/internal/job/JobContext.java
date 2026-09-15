package dev.example.mapi.internal.job;

import java.util.Map;

/**
 * Passed to every {@link JobBody}; the only sanctioned way for a body to
 * report progress and observe cancellation/deadline decisions.
 */
public interface JobContext {

    /** @return the owning job's identifier */
    String jobId();

    /** @return true once cancellation or deadline revocation was requested */
    boolean isCancelled();

    /**
     * @return remaining wall-clock milliseconds before the deadline, or
     *     {@code Long.MAX_VALUE} when no deadline was set
     */
    long remainingWallMs();

    /**
     * @throws java.util.concurrent.CancellationException when cancellation
     *     was requested
     */
    void checkCancelled();

    /**
     * @throws dev.example.mapi.internal.problem.ProblemException with
     *     {@code DEADLINE_EXCEEDED} when the wall-clock deadline passed
     */
    void checkDeadline();

    /**
     * Reports a named progress point. Milestones are appended in order and
     * are visible in the job view.
     *
     * @param name    milestone name, never blank
     * @param details structured detail, may be {@code null}
     */
    void milestone(String name, Map<String, Object> details);
}
