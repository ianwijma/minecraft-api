package dev.example.mapi.internal.job;

/**
 * The work performed by a job. Bodies must be cooperative: long work should
 * poll {@link JobContext#checkCancelled()} / {@link JobContext#checkDeadline()}
 * so cancellation and wall-clock deadlines take effect promptly. A body that
 * ignores them is finalized as failed once it returns, and the wall-clock
 * deadline is still enforced at the API level.
 *
 * @param <T> result type; must be JSON-able ({@code JsonWriter} rules)
 */
@FunctionalInterface
public interface JobBody<T> {

    /**
     * Runs the job body.
     *
     * @param context the job context, never {@code null}
     * @return the JSON-able result payload
     * @throws Exception on failure; {@code ProblemException} carries its
     *     problem code into the job view, anything else becomes
     *     {@code INTERNAL}
     */
    T run(JobContext context) throws Exception;
}
