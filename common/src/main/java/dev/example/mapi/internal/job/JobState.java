package dev.example.mapi.internal.job;

/** Lifecycle states of a managed job. Terminal states are final. */
public enum JobState {
    /** Queued, not yet started. */
    PENDING,
    /** Body is executing. */
    RUNNING,
    /** Body completed with a result. */
    SUCCEEDED,
    /** Body failed or a system condition (deadline) stopped it. */
    FAILED,
    /** Cancelled by the caller or by a lifecycle event (see {@code failureCode}). */
    CANCELLED
}
