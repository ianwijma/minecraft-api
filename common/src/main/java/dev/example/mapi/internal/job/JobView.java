package dev.example.mapi.internal.job;

import dev.example.mapi.internal.problem.ProblemCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable point-in-time view of a job, safe to hand across threads and to
 * serialize.
 *
 * @param id                    job identifier, never blank
 * @param kind                  free-form operation kind, never blank
 * @param worldSessionId        world this job is scoped to, if any
 * @param state                 current state, never {@code null}
 * @param cancellationRequested true once someone asked the body to stop
 * @param submittedEpochMs      submission time (wall clock)
 * @param startedEpochMs        when the body started, if it started
 * @param endedEpochMs          when the job reached a terminal state
 * @param deadlineEpochMs       wall-clock deadline, if one was set
 * @param failureCode           problem code for {@code FAILED} jobs or for
 *                              lifecycle cancellations such as
 *                              {@code WORLD_UNLOADED}
 * @param failureMessage        human-readable failure detail
 * @param result                JSON-able result payload for successful jobs
 * @param milestones            ordered milestones reported by the body
 */
public record JobView(
        String id,
        String kind,
        Optional<String> worldSessionId,
        JobState state,
        boolean cancellationRequested,
        long submittedEpochMs,
        Optional<Long> startedEpochMs,
        Optional<Long> endedEpochMs,
        Optional<Long> deadlineEpochMs,
        Optional<ProblemCode> failureCode,
        Optional<String> failureMessage,
        Object result,
        List<Milestone> milestones) {

    /** A named progress point reported by a running job. */
    public record Milestone(String name, Map<String, Object> details, long atEpochMs) {
    }

    /** @return true when the job will not change state anymore */
    public boolean terminal() {
        return state == JobState.SUCCEEDED || state == JobState.FAILED || state == JobState.CANCELLED;
    }

    /**
     * @return the view as an ordered map for JSON serialization; optional
     *     fields are omitted when absent
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("kind", kind);
        worldSessionId.ifPresent(value -> map.put("worldSessionId", value));
        map.put("state", state.name());
        map.put("cancellationRequested", cancellationRequested);
        map.put("submittedAtEpochMs", submittedEpochMs);
        startedEpochMs.ifPresent(value -> map.put("startedAtEpochMs", value));
        endedEpochMs.ifPresent(value -> map.put("endedAtEpochMs", value));
        deadlineEpochMs.ifPresent(value -> map.put("deadlineAtEpochMs", value));
        failureCode.ifPresent(value -> map.put("failureCode", value.wireCode()));
        failureMessage.ifPresent(value -> map.put("failureMessage", value));
        if (result != null) {
            map.put("result", result);
        }
        if (!milestones.isEmpty()) {
            map.put("milestones", milestones.stream().map(milestone -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", milestone.name());
                if (!milestone.details().isEmpty()) {
                    m.put("details", milestone.details());
                }
                m.put("atEpochMs", milestone.atEpochMs());
                return m;
            }).toList());
        }
        return map;
    }
}
