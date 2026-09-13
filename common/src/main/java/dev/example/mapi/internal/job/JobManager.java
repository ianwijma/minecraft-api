package dev.example.mapi.internal.job;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loader-neutral job system: bounded, cooperative jobs with wall-clock
 * deadlines, milestones, cancellation, and world-scoped termination
 * (spec §5, §6, §13.2). Jobs execute serially on one worker thread because
 * their bodies ultimately serialize on the game thread anyway.
 *
 * <p>Deadlines are wall-clock ({@link System#nanoTime}), never game ticks
 * (spec §4.3). A watchdog revokes running jobs immediately at the API level;
 * bodies observe the decision through {@link JobContext}. Non-cooperative
 * bodies are finalized as failed on completion.
 */
public final class JobManager {

    /** Default number of terminal job views retained for inspection. */
    public static final int DEFAULT_RETENTION = 256;

    private static final long WATCHDOG_PERIOD_MS = 50;

    private final ExecutorService worker;
    private final ScheduledExecutorService watchdog;
    private final ConcurrentHashMap<String, Entry> jobs = new ConcurrentHashMap<>();
    private final ArrayDeque<String> completedOrder = new ArrayDeque<>();
    private final AtomicLong counter = new AtomicLong();
    private final int retention;
    private volatile boolean shutdown;

    /** Creates a manager with {@link #DEFAULT_RETENTION}. */
    public JobManager() {
        this(DEFAULT_RETENTION);
    }

    /**
     * @param completedRetention how many terminal job views are retained
     */
    public JobManager(int completedRetention) {
        if (completedRetention < 1) {
            throw new IllegalArgumentException("retention must be at least 1");
        }
        this.retention = completedRetention;
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "mapi-jobs");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mapi-jobs-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        this.watchdog.scheduleAtFixedRate(this::watchdogTick,
                WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Submits a job.
     *
     * @param kind            free-form kind label, never blank
     * @param worldSessionId  world scope, empty for process-scoped jobs
     * @param deadlineEpochMs wall-clock deadline in epoch millis; non-positive
     *                        means no deadline
     * @param body            the work, never {@code null}
     * @return a handle to the job
     */
    public JobHandle submit(String kind, Optional<String> worldSessionId, long deadlineEpochMs, JobBody<?> body) {
        Objects.requireNonNull(body, "body");
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (shutdown) {
            throw new IllegalStateException("job manager is shut down");
        }
        String id = "job-" + counter.incrementAndGet();
        Entry entry = new Entry(id, kind, worldSessionId == null ? Optional.empty() : worldSessionId,
                deadlineEpochMs, body);
        jobs.put(id, entry);
        entry.future = worker.submit(() -> run(entry));
        return new JobHandle(this, id);
    }

    /** @return the current view, or empty when the job was already dropped */
    public Optional<JobView> view(String id) {
        Entry entry = jobs.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.snapshot());
    }

    /**
     * Requests cancellation. Pending jobs never run; running jobs observe the
     * decision cooperatively via their context.
     *
     * @param id job identifier
     * @return true when the job existed and was not already terminal
     */
    public boolean cancel(String id) {
        Entry entry = jobs.get(id);
        if (entry == null) {
            return false;
        }
        return entry.requestCancel(null);
    }

    /**
     * Terminates every outstanding job scoped to {@code worldSessionId} with
     * reason {@code WORLD_UNLOADED} (spec §6). Pending jobs never run; running
     * bodies are asked to stop cooperatively.
     *
     * @param worldSessionId the world that unloaded
     * @return the number of outstanding jobs terminated
     */
    public int terminateWorld(String worldSessionId) {
        Objects.requireNonNull(worldSessionId, "worldSessionId");
        int count = 0;
        for (Entry entry : jobs.values()) {
            if (worldSessionId.equals(entry.worldSessionId.orElse(null)) && !entry.snapshot().terminal()) {
                if (entry.requestCancel(ProblemCode.WORLD_UNLOADED)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Waits for a terminal state.
     *
     * @param id        job identifier
     * @param timeoutMs wall-clock bound in milliseconds
     * @return the terminal view, or empty on timeout or if the job was dropped
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public Optional<JobView> waitFor(String id, long timeoutMs) throws InterruptedException {
        Entry entry = jobs.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return Optional.empty();
        }
        return Optional.of(entry.snapshot());
    }

    /** Stops accepting work, cancels pending jobs, and shuts the executors down. Idempotent. */
    public void shutdown() {
        shutdown = true;
        watchdog.shutdownNow();
        worker.shutdownNow();
        for (Entry entry : jobs.values()) {
            entry.finalizeCancelled(null);
        }
    }

    private void run(Entry entry) {
        if (!entry.beginRun()) {
            return;
        }
        try {
            Object result = entry.body.run(entry.context());
            entry.finalizeSuccess(result);
        } catch (ProblemException e) {
            entry.finalizeFailure(e.code(), e.getMessage());
        } catch (CancellationException e) {
            entry.finalizeCancelled(null);
        } catch (Throwable t) {
            entry.finalizeFailure(ProblemCode.INTERNAL, String.valueOf(t));
        }
    }

    private void watchdogTick() {
        try {
            long now = System.currentTimeMillis();
            for (Entry entry : jobs.values()) {
                entry.enforceDeadline(now);
            }
        } catch (RuntimeException ignored) {
            // The watchdog must never die; next tick retries.
        }
    }

    private void recordCompletion(Entry entry) {
        synchronized (completedOrder) {
            completedOrder.addLast(entry.id);
            while (completedOrder.size() > retention) {
                jobs.remove(completedOrder.pollFirst());
            }
        }
    }

    private final class Entry {

        private final String id;
        private final String kind;
        private final Optional<String> worldSessionId;
        private final long deadlineEpochMs;
        private final JobBody<?> body;
        private final CountDownLatch done = new CountDownLatch(1);
        private final List<JobView.Milestone> milestones = new CopyOnWriteArrayList<>();

        private Future<?> future;
        private JobState state = JobState.PENDING;
        private volatile boolean cancelRequested;
        private volatile boolean worldUnloadRequested;
        private volatile boolean deadlineMissed;
        private long submittedAtEpochMs = System.currentTimeMillis();
        private long startedAtEpochMs;
        private long endedAtEpochMs;
        private ProblemCode failureCode;
        private String failureMessage;
        private Object result;

        private Entry(String id, String kind, Optional<String> worldSessionId,
                long deadlineEpochMs, JobBody<?> body) {
            this.id = id;
            this.kind = kind;
            this.worldSessionId = worldSessionId;
            this.deadlineEpochMs = deadlineEpochMs;
            this.body = body;
        }

        private synchronized boolean beginRun() {
            if (state != JobState.PENDING) {
                return false;
            }
            state = JobState.RUNNING;
            startedAtEpochMs = System.currentTimeMillis();
            return true;
        }

        private void enforceDeadline(long nowEpochMs) {
            if (deadlineEpochMs <= 0 || nowEpochMs < deadlineEpochMs) {
                return;
            }
            JobState current = snapshot().state();
            if (current == JobState.PENDING) {
                deadlineMissed = true;
                if (future != null) {
                    future.cancel(false);
                }
                finalizeFailure(ProblemCode.DEADLINE_EXCEEDED, "deadline elapsed before the job started");
            } else if (current == JobState.RUNNING) {
                deadlineMissed = true;
                cancelRequested = true;
            }
        }

        private boolean requestCancel(ProblemCode lifecycleCode) {
            synchronized (this) {
                if (state == JobState.PENDING) {
                    cancelRequested = true;
                    if (lifecycleCode == ProblemCode.WORLD_UNLOADED) {
                        worldUnloadRequested = true;
                    }
                    if (future != null) {
                        future.cancel(false);
                    }
                    state = JobState.CANCELLED;
                    failureCode = lifecycleCode;
                    endedAtEpochMs = System.currentTimeMillis();
                    done.countDown();
                    return true;
                }
                if (state == JobState.RUNNING) {
                    cancelRequested = true;
                    if (lifecycleCode == ProblemCode.WORLD_UNLOADED) {
                        worldUnloadRequested = true;
                    }
                    return true;
                }
                return false;
            }
        }

        private synchronized void finalizeSuccess(Object value) {
            if (state != JobState.RUNNING) {
                return;
            }
            endedAtEpochMs = System.currentTimeMillis();
            if (deadlineMissed) {
                state = JobState.FAILED;
                failureCode = ProblemCode.DEADLINE_EXCEEDED;
                failureMessage = "work finished after the wall-clock deadline; result discarded";
            } else if (worldUnloadRequested) {
                state = JobState.CANCELLED;
                failureCode = ProblemCode.WORLD_UNLOADED;
            } else if (cancelRequested) {
                state = JobState.CANCELLED;
            } else {
                state = JobState.SUCCEEDED;
                result = value;
            }
            done.countDown();
            recordCompletion(this);
        }

        private synchronized void finalizeFailure(ProblemCode code, String message) {
            if (state == JobState.PENDING || state == JobState.RUNNING) {
                state = JobState.FAILED;
                failureCode = code;
                failureMessage = message;
                endedAtEpochMs = System.currentTimeMillis();
                done.countDown();
                recordCompletion(this);
            }
        }

        private synchronized void finalizeCancelled(ProblemCode lifecycleCode) {
            if (state == JobState.PENDING || state == JobState.RUNNING) {
                state = JobState.CANCELLED;
                failureCode = worldUnloadRequested ? ProblemCode.WORLD_UNLOADED : lifecycleCode;
                endedAtEpochMs = System.currentTimeMillis();
                done.countDown();
                recordCompletion(this);
            }
        }

        private synchronized JobView snapshot() {
            return new JobView(id, kind, worldSessionId, state, cancelRequested,
                    submittedAtEpochMs,
                    startedAtEpochMs == 0 ? Optional.empty() : Optional.of(startedAtEpochMs),
                    endedAtEpochMs == 0 ? Optional.empty() : Optional.of(endedAtEpochMs),
                    deadlineEpochMs <= 0 ? Optional.empty() : Optional.of(deadlineEpochMs),
                    Optional.ofNullable(failureCode),
                    Optional.ofNullable(failureMessage),
                    result,
                    List.copyOf(milestones));
        }

        private JobContext context() {
            return new JobContext() {
                @Override
                public String jobId() {
                    return id;
                }

                @Override
                public boolean isCancelled() {
                    return cancelRequested;
                }

                @Override
                public long remainingWallMs() {
                    if (deadlineEpochMs <= 0) {
                        return Long.MAX_VALUE;
                    }
                    return Math.max(0, deadlineEpochMs - System.currentTimeMillis());
                }

                @Override
                public void checkCancelled() {
                    if (cancelRequested) {
                        throw new CancellationException("job cancelled: " + id);
                    }
                }

                @Override
                public void checkDeadline() {
                    if (deadlineMissed) {
                        throw new ProblemException(ProblemCode.DEADLINE_EXCEEDED,
                                "wall-clock deadline elapsed");
                    }
                }

                @Override
                public void milestone(String name, Map<String, Object> details) {
                    if (name == null || name.isBlank()) {
                        throw new IllegalArgumentException("milestone name must not be blank");
                    }
                    milestones.add(new JobView.Milestone(name,
                            details == null ? Map.of() : Map.copyOf(details),
                            System.currentTimeMillis()));
                }
            };
        }
    }

    /** @return true once {@link #shutdown()} was called */
    public boolean isShutdown() {
        return shutdown;
    }
}
