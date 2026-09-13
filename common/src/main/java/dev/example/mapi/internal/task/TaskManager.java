package dev.example.mapi.internal.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Task registry and executor implementing the MAPI task protocol (spec §3.1):
 * tasks move {@code queued -> running -> succeeded | failed}, with
 * cooperative cancellation ({@code cancelRequested -> cancelled}) and a
 * wall-time deadline ({@code expired}). Cancellation is not a rollback:
 * results describe what already happened, partial effects are kept.
 *
 * <p>Work runs on a small dedicated daemon pool; kinds that touch game state
 * must schedule that work onto the owning thread themselves (never block a
 * game thread). When a world session ends, running tasks fail with
 * {@code LIFECYCLE_CHANGED}.
 */
public final class TaskManager {

    /** Terminal or intermediate task states, in protocol order. */
    public enum State {
        QUEUED, RUNNING, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED, EXPIRED;

        boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == EXPIRED;
        }

        /** @return true when the state can no longer change */
        public boolean isTerminalState() {
            return isTerminal();
        }

        /** @return the wire name used in JSON payloads (spec §3.1) */
        public String wireName() {
            return switch (this) {
                case QUEUED -> "queued";
                case RUNNING -> "running";
                case SUCCEEDED -> "succeeded";
                case FAILED -> "failed";
                case CANCEL_REQUESTED -> "cancelRequested";
                case CANCELLED -> "cancelled";
                case EXPIRED -> "expired";
            };
        }

        /**
         * @param wireName state name from a request
         * @return the matching state or {@code null}
         */
        public static State fromWireName(String wireName) {
            for (State state : values()) {
                if (state.wireName().equals(wireName)) {
                    return state;
                }
            }
            return null;
        }
    }

    /** Cooperative cancellation signal raised inside {@link TaskKind#execute}. */
    public static final class TaskCancelledException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /**
     * A registered task kind. Implementations must be stateless; all
     * per-task state flows through {@link TaskContext}.
     */
    public interface TaskKind {

        /**
         * @return the kind identifier used in {@code POST /tasks} bodies,
         *         matching {@code [a-z][a-z0-9.-]{1,63}}
         */
        String kind();

        /**
         * Executes the task. Must honor {@link TaskContext#checkCancelled()}
         * and {@link TaskContext#deadlineEpochMs()} cooperatively and finish
         * exactly one of: {@link TaskContext#succeeded}, {@link TaskContext#failed},
         * or the cancelled/expired outcomes.
         *
         * @param context per-task context, never {@code null}
         * @throws Exception implementation errors become a {@code failed} task
         */
        void execute(TaskContext context) throws Exception;
    }

    /**
     * Mutable per-task view handed to a {@link TaskKind}.
     */
    public interface TaskContext {

        /** @return the submitted payload (kind-specific), never {@code null} */
        Map<String, Object> payload();

        /** @return wall-clock epoch ms after which the task expires */
        long deadlineEpochMs();

        /**
         * Reports progress. {@code estimated} marks whether {@code total} is
         * an estimate rather than an exact unit count.
         */
        void progress(long unitsDone, Long totalUnits, boolean estimated);

        /**
         * Records a partial effect that already happened (cancellation never
         * removes it from the result).
         */
        void recordPartialEffect(Map<String, Object> effect);

        /** Records a performed cleanup step. */
        void recordCleanup(String step);

        /**
         * Raises {@link TaskCancelledException} when cancellation or expiry
         * was requested; call it regularly from loops.
         */
        void checkCancelled();

        /** Completes the task successfully with a JSON-serializable result. */
        void succeeded(Object result);

        /** Fails the task with a protocol error code and message. */
        void failed(String code, String message);
    }

    /**
     * Immutable task description returned by reads and after mutations.
     */
    public record TaskSnapshot(
            String id,
            String kind,
            String state,
            long createdAtEpochMs,
            Long deadlineEpochMs,
            Long finishedAtEpochMs,
            Map<String, Object> progress,
            Object result,
            List<Map<String, Object>> partialEffects,
            List<String> cleanup,
            Map<String, String> error) {
    }

    private static final class TaskState {

        final String id;
        final String kind;
        final Map<String, Object> payload;
        final long createdAtEpochMs = System.currentTimeMillis();
        final long deadlineEpochMs;
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        volatile State state = State.QUEUED;
        volatile long finishedAtEpochMs;
        volatile long progressUnits;
        volatile Long progressTotal;
        volatile boolean progressEstimated;
        volatile Object result;
        volatile String errorCode;
        volatile String errorMessage;
        final List<Map<String, Object>> partialEffects = new ArrayList<>();
        final List<String> cleanup = new ArrayList<>();

        TaskState(String kind, Map<String, Object> payload, long deadlineEpochMs) {
            this.id = UUID.randomUUID().toString();
            this.kind = kind;
            this.payload = payload;
            this.deadlineEpochMs = deadlineEpochMs;
        }

        synchronized TaskSnapshot snapshot() {
            Map<String, Object> progressMap = null;
            if (state == State.RUNNING || state == State.CANCEL_REQUESTED || state.isTerminal()) {
                progressMap = new LinkedHashMap<>();
                progressMap.put("units", progressUnits);
                if (progressTotal != null) {
                    progressMap.put("total", progressTotal);
                }
                progressMap.put("estimated", progressEstimated);
            }
            Map<String, String> errorMap = null;
            if (errorCode != null) {
                errorMap = new LinkedHashMap<>();
                errorMap.put("code", errorCode);
                errorMap.put("message", errorMessage == null ? "" : errorMessage);
            }
            return new TaskSnapshot(id, kind, state.wireName(),
                    createdAtEpochMs, deadlineEpochMs, finishedAtEpochMs == 0 ? null : finishedAtEpochMs,
                    progressMap, state == State.SUCCEEDED ? result : null,
                    List.copyOf(partialEffects), List.copyOf(cleanup), errorMap);
        }
    }

    private final Logger logger;
    private final java.util.function.BiConsumer<String, Map<String, Object>> eventSink;
    private final ConcurrentMap<String, TaskKind> kinds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicLong tasksCreated = new AtomicLong();
    private volatile java.util.concurrent.ScheduledExecutorService executor;
    private static final int MAX_RETAINED_TASKS = 1000;

    /**
     * @param logger    platform logger; secrets are never logged
     * @param eventSink optional observer for {@code task.state_changed}
     *                  events (type, data); failures are ignored
     */
    public TaskManager(Logger logger, java.util.function.BiConsumer<String, Map<String, Object>> eventSink) {
        this.logger = logger;
        this.eventSink = eventSink;
    }

    /**
     * Registers a task kind. Re-registering the same kind replaces the
     * previous implementation (dev-loop convenience); running tasks are
     * unaffected.
     *
     * @param kind the kind implementation, never {@code null}
     */
    public void registerKind(TaskKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.kind().matches("[a-z][a-z0-9.-]{1,63}")) {
            throw new IllegalArgumentException("Task kind id must match [a-z][a-z0-9.-]{1,63}: " + kind.kind());
        }
        kinds.put(kind.kind(), kind);
    }

    /**
     * @return true when the kind is registered
     */
    public boolean hasKind(String kind) {
        return kind != null && kinds.containsKey(kind);
    }

    /**
     * Submits a task for execution.
     *
     * @param kind       registered kind id
     * @param payload    kind-specific payload (validated by the kind)
     * @param deadlineMs relative deadline in ms; clamped to [100, 86_400_000]
     * @return the initial {@code queued} snapshot
     * @throws IllegalArgumentException when the kind is unknown
     */
    public TaskSnapshot submit(String kind, Map<String, Object> payload, Long deadlineMs) {
        TaskKind taskKind = kinds.get(kind);
        if (taskKind == null) {
            throw new IllegalArgumentException("unknown task kind: " + kind);
        }
        long deadline = System.currentTimeMillis()
                + Math.clamp(deadlineMs == null ? 30_000L : deadlineMs, 100L, 86_400_000L);
        TaskState state = new TaskState(kind, payload == null ? Map.of() : payload, deadline);
        tasks.put(state.id, state);
        if (tasks.size() > MAX_RETAINED_TASKS) {
            tasks.values().stream()
                    .filter(t -> t.state.isTerminal())
                    .sorted(java.util.Comparator.comparingLong(t -> t.createdAtEpochMs))
                    .limit(tasks.size() - MAX_RETAINED_TASKS)
                    .forEach(t -> tasks.remove(t.id));
        }
        executor().execute(() -> run(state, taskKind));
        scheduleExpiry(state);
        tasksCreated.incrementAndGet();
        emit("task.state_changed", state, null);
        return state.snapshot();
    }

    private void emit(String type, TaskState state, String extraError) {
        if (eventSink == null) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("taskId", state.id);
            data.put("kind", state.kind);
            data.put("state", state.state.wireName());
            if (extraError != null) {
                data.put("errorCode", extraError);
            }
            eventSink.accept(type, data);
        } catch (RuntimeException ignored) {
            // Observers must never break task execution.
        }
    }

    private java.util.concurrent.ScheduledExecutorService executor() {
        java.util.concurrent.ScheduledExecutorService pool = executor;
        if (pool == null) {
            synchronized (this) {
                pool = executor;
                if (pool == null) {
                    pool = java.util.concurrent.Executors.newScheduledThreadPool(2, runnable -> {
                        Thread thread = new Thread(runnable, "mapi-task");
                        thread.setDaemon(true);
                        return thread;
                    });
                    executor = pool;
                }
            }
        }
        return pool;
    }

    private void run(TaskState state, TaskKind kind) {
        state.state = State.RUNNING;
        try {
            kind.execute(new Context(state));
        } catch (TaskCancelledException e) {
            finish(state, State.CANCELLED, null, "CANCELLED", "Cancelled by request; partial effects are kept.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish(state, State.FAILED, null, "INTERNAL", "Task thread interrupted.");
        } catch (Exception e) {
            logger.warn("MAPI: task {} (kind {}) failed", state.id, state.kind, e);
            finish(state, State.FAILED, null, "INTERNAL",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void scheduleExpiry(TaskState state) {
        long delay = Math.max(0, state.deadlineEpochMs - System.currentTimeMillis());
        executor().schedule(() -> {
            if (!state.state.isTerminal()) {
                finish(state, State.EXPIRED, null, "DEADLINE_EXCEEDED",
                        "Task did not finish before its wall-time deadline.");
            }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels a task. Returns false when the task is unknown, true otherwise
     * (the state may still be terminal if it already finished).
     *
     * @param id task id
     * @return the post-cancellation snapshot, or {@code null} when unknown
     */
    public TaskSnapshot cancel(String id) {
        TaskState state = tasks.get(id);
        if (state == null) {
            return null;
        }
        if (!state.state.isTerminal() && state.state != State.CANCEL_REQUESTED) {
            state.state = State.CANCEL_REQUESTED;
            state.cancelRequested.set(true);
        }
        return state.snapshot();
    }

    /**
     * @return the snapshot for the task, or {@code null} when unknown
     */
    public TaskSnapshot get(String id) {
        TaskState state = tasks.get(id);
        return state == null ? null : state.snapshot();
    }

    /**
     * Lists task snapshots newest-first, optionally filtered by state.
     *
     * @param stateFilter state name or {@code null} for all
     * @param limit       page size, clamped to [1, 200]
     * @return snapshots newest-first plus truncation metadata
     */
    public Listing list(String stateFilter, int limit) {
        int effectiveLimit = Math.clamp(limit <= 0 ? 50 : limit, 1, 200);
        List<TaskSnapshot> all = tasks.values().stream()
                .filter(t -> stateFilter == null || t.state.wireName().equals(stateFilter))
                .sorted(java.util.Comparator.comparingLong((TaskState t) -> t.createdAtEpochMs).reversed())
                .map(TaskState::snapshot)
                .toList();
        List<TaskSnapshot> page = all.subList(0, Math.min(effectiveLimit, all.size()));
        return new Listing(List.copyOf(page), all.size() > page.size(), all.size());
    }

    /**
     * Listing result.
     *
     * @param tasks     page of snapshots newest-first
     * @param truncated true when more tasks exist beyond the page
     * @param total     total matching tasks
     */
    public record Listing(List<TaskSnapshot> tasks, boolean truncated, int total) {
    }

    /**
     * Fails all non-terminal tasks with {@code LIFECYCLE_CHANGED}. Called
     * when a world session ends or the API shuts down.
     */
    public void failAllForLifecycleChange(String reason) {
        for (TaskState state : tasks.values()) {
            if (!state.state.isTerminal()) {
                state.cancelRequested.set(true);
                finish(state, State.FAILED, null, "LIFECYCLE_CHANGED", reason);
            }
        }
    }

    /**
     * Fails running tasks and shuts the executor down. Called from
     * {@code MapiRuntime.shutdown()}.
     */
    public synchronized void shutdown() {
        failAllForLifecycleChange("MAPI is shutting down; tasks did not run to completion.");
        java.util.concurrent.ScheduledExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void finish(TaskState state, State terminal, Object result, String errorCode, String errorMessage) {
        if (state.state.isTerminal()) {
            return;
        }
        synchronized (state) {
            if (state.state.isTerminal()) {
                return;
            }
            state.state = terminal;
            state.finishedAtEpochMs = System.currentTimeMillis();
            if (terminal == State.SUCCEEDED) {
                state.result = result;
            }
            state.errorCode = errorCode;
            state.errorMessage = errorMessage;
        }
        emit("task.state_changed", state, errorCode);
    }

    private final class Context implements TaskContext {

        private final TaskState state;

        Context(TaskState state) {
            this.state = state;
        }

        @Override
        public Map<String, Object> payload() {
            return state.payload;
        }

        @Override
        public long deadlineEpochMs() {
            return state.deadlineEpochMs;
        }

        @Override
        public void progress(long unitsDone, Long totalUnits, boolean estimated) {
            state.progressUnits = unitsDone;
            state.progressTotal = totalUnits;
            state.progressEstimated = estimated;
        }

        @Override
        public void recordPartialEffect(Map<String, Object> effect) {
            synchronized (state) {
                state.partialEffects.add(new LinkedHashMap<>(effect));
            }
        }

        @Override
        public void recordCleanup(String step) {
            synchronized (state) {
                state.cleanup.add(step);
            }
        }

        @Override
        public void checkCancelled() {
            if (state.cancelRequested.get()) {
                throw new TaskCancelledException();
            }
            if (System.currentTimeMillis() >= state.deadlineEpochMs) {
                finish(state, State.EXPIRED, null, "DEADLINE_EXCEEDED",
                        "Task did not finish before its wall-time deadline.");
                throw new TaskCancelledException();
            }
        }

        @Override
        public void succeeded(Object result) {
            finish(state, State.SUCCEEDED, result, null, null);
        }

        @Override
        public void failed(String code, String message) {
            finish(state, State.FAILED, null, code, message);
        }
    }

    /**
     * @return total tasks ever created (observability)
     */
    public long totalCreated() {
        return tasksCreated.get();
    }
}
