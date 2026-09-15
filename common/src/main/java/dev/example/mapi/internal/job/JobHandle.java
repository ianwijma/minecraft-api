package dev.example.mapi.internal.job;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A job submitted to the {@link JobManager}. All accessor methods delegate to
 * the manager and are safe from any thread.
 */
public final class JobHandle {

    private final JobManager manager;
    private final String id;

    JobHandle(JobManager manager, String id) {
        this.manager = manager;
        this.id = id;
    }

    /** @return the job identifier */
    public String id() {
        return id;
    }

    /** @return the current view, or empty when the job was already dropped */
    public Optional<JobView> view() {
        return manager.view(id);
    }

    /**
     * Requests cancellation (cooperative for running jobs).
     *
     * @return true when the job existed and was not already terminal
     */
    public boolean cancel() {
        return manager.cancel(id);
    }

    /**
     * Waits for a terminal state.
     *
     * @param timeoutMs wall-clock bound in milliseconds
     * @return the terminal view, or empty on timeout or if the job was
     *     dropped
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public Optional<JobView> waitFor(long timeoutMs) throws InterruptedException {
        return manager.waitFor(id, timeoutMs);
    }

    /** @return the milestone list from the current view, possibly empty */
    public List<JobView.Milestone> milestones() {
        return view().map(JobView::milestones).orElse(List.of());
    }

    /**
     * Convenience for test and runner code: the latest view's result cast to
     * the expected type.
     *
     * @param type expected result class
     * @param <T>  result type
     * @return the result, or {@code null} when absent
     */
    @SuppressWarnings("unchecked")
    public <T> T resultAs(Class<T> type) {
        return view().map(view -> (T) view.result()).orElse(null);
    }

    /**
     * @param key   milestone detail key
     * @param value expected value
     * @return true when any milestone carries this key/value pair
     */
    public boolean hasMilestoneDetail(String key, Object value) {
        return milestones().stream()
                .anyMatch(m -> m.details().getOrDefault(key, "").equals(value));
    }

    /**
     * Waits and returns milestone detail map keyed by milestone name.
     *
     * @param timeoutMs wall-clock bound
     * @return milestone details by name from the terminal (or current) view
     * @throws InterruptedException when interrupted
     */
    public Map<String, Map<String, Object>> milestoneDetails(long timeoutMs) throws InterruptedException {
        waitFor(timeoutMs);
        var out = new java.util.LinkedHashMap<String, Map<String, Object>>();
        for (JobView.Milestone m : milestones()) {
            out.put(m.name(), m.details());
        }
        return out;
    }
}
