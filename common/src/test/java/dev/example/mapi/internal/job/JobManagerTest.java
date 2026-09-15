package dev.example.mapi.internal.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JobManagerTest {

    private JobManager manager = new JobManager();

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    private static final long NOW = System.currentTimeMillis();

    @Test
    void successfulJobCarriesResultAndMilestones() throws Exception {
        JobHandle handle = manager.submit("test.work", Optional.empty(), 0, context -> {
            context.milestone("half", Map.of("step", 1));
            context.milestone("done", Map.of("step", 2));
            return Map.of("value", 42);
        });
        JobView view = handle.waitFor(5000).orElseThrow();
        assertEquals(JobState.SUCCEEDED, view.state());
        assertEquals(42, ((Map<?, ?>) view.result()).get("value"));
        assertEquals(2, view.milestones().size());
        assertEquals("half", view.milestones().get(0).name());
        assertTrue(view.startedEpochMs().isPresent());
        assertTrue(view.endedEpochMs().isPresent());
        assertTrue(view.deadlineEpochMs().isEmpty());
        assertTrue(view.terminal());
    }

    @Test
    void problemExceptionBecomesFailedWithCode() throws Exception {
        JobHandle handle = manager.submit("test.fail", Optional.empty(), 0, context -> {
            throw new ProblemException(ProblemCode.STALE_WORLD, "world went away");
        });
        JobView view = handle.waitFor(5000).orElseThrow();
        assertEquals(JobState.FAILED, view.state());
        assertEquals(ProblemCode.STALE_WORLD, view.failureCode().orElseThrow());
        assertEquals("world went away", view.failureMessage().orElseThrow());
    }

    @Test
    void genericExceptionBecomesInternalFailure() throws Exception {
        JobHandle handle = manager.submit("test.crash", Optional.empty(), 0, context -> {
            throw new IllegalStateException("boom");
        });
        JobView view = handle.waitFor(5000).orElseThrow();
        assertEquals(JobState.FAILED, view.state());
        assertEquals(ProblemCode.INTERNAL, view.failureCode().orElseThrow());
    }

    @Test
    void cancelBeforeStartNeverRunsTheBody() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        JobHandle blocker = manager.submit("test.block", Optional.empty(), 0, context -> {
            started.countDown();
            Thread.sleep(5000);
            return "never";
        });
        // Block the single worker, then queue a second job and cancel it.
        assertTrue(started.await(2, TimeUnit.SECONDS));
        JobHandle queued = manager.submit("test.queued", Optional.empty(), 0, context -> "ran");
        assertTrue(queued.cancel());
        JobView view = queued.waitFor(2000).orElseThrow();
        assertEquals(JobState.CANCELLED, view.state());
        assertTrue(view.failureCode().isEmpty());
        assertTrue(view.startedEpochMs().isEmpty());
        blocker.cancel();
    }

    @Test
    void cooperativeCancelWhileRunning() throws Exception {
        JobHandle handle = manager.submit("test.coop", Optional.empty(), 0, context -> {
            while (!context.isCancelled()) {
                Thread.sleep(10);
            }
            context.checkCancelled();
            return "never";
        });
        awaitState(handle, JobState.RUNNING);
        assertTrue(handle.cancel());
        JobView view = handle.waitFor(5000).orElseThrow();
        assertEquals(JobState.CANCELLED, view.state());
        assertTrue(view.cancellationRequested());
    }

    @Test
    void wallClockDeadlineRevokesRunningWorkViaContext() throws Exception {
        long deadline = System.currentTimeMillis() + 200;
        JobHandle handle = manager.submit("test.deadline", Optional.empty(), deadline, context -> {
            while (context.remainingWallMs() > 50) {
                Thread.sleep(10);
            }
            Thread.sleep(400);
            context.checkDeadline();
            return "too late";
        });
        JobView view = handle.waitFor(5000).orElseThrow();
        assertEquals(JobState.FAILED, view.state());
        assertEquals(ProblemCode.DEADLINE_EXCEEDED, view.failureCode().orElseThrow());
        assertTrue(view.deadlineEpochMs().isPresent());
    }

    @Test
    void deadlineRevokesQueuedWorkWithoutRunningIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        JobHandle blocker = manager.submit("test.block2", Optional.empty(), 0, context -> {
            started.countDown();
            Thread.sleep(5000);
            return "never";
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        JobHandle queued = manager.submit("test.queued2", Optional.empty(),
                System.currentTimeMillis() + 100, context -> "ran");
        JobView view = queued.waitFor(5000).orElseThrow();
        assertEquals(JobState.FAILED, view.state());
        assertEquals(ProblemCode.DEADLINE_EXCEEDED, view.failureCode().orElseThrow());
        assertTrue(view.startedEpochMs().isEmpty());
        blocker.cancel();
    }

    @Test
    void worldUnloadTerminatesOutstandingWorldScopedJobs() throws Exception {
        CountDownLatch blockerRunning = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        JobHandle blocker = manager.submit("test.block3", Optional.empty(), 0, context -> {
            blockerRunning.countDown();
            releaseBlocker.await(5, TimeUnit.SECONDS);
            return "blocker";
        });
        assertTrue(blockerRunning.await(2, TimeUnit.SECONDS));

        // Both world-scoped jobs queue behind the blocker: one stays pending,
        // and we verify cooperative termination for the second by cancelling
        // it after the blocker is released (see cooperativeCancelWhileRunning
        // for a genuinely running body).
        JobHandle pending = manager.submit("test.w1", Optional.of("world-1"), 0, context -> "x");
        JobHandle queued = manager.submit("test.w2", Optional.of("world-1"), 0, context -> "y");
        JobHandle other = manager.submit("test.w3", Optional.of("world-2"), 0, context -> "z");

        assertEquals(2, manager.terminateWorld("world-1"));

        for (JobHandle handle : java.util.List.of(pending, queued)) {
            JobView view = handle.waitFor(2000).orElseThrow();
            assertEquals(JobState.CANCELLED, view.state());
            assertEquals(ProblemCode.WORLD_UNLOADED, view.failureCode().orElseThrow());
        }

        releaseBlocker.countDown();
        assertTrue(other.waitFor(5000).orElseThrow().state() == JobState.SUCCEEDED);
        blocker.cancel();
    }

    @Test
    void waitForTimesOutWithoutTerminalState() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobHandle handle = manager.submit("test.slow", Optional.empty(), 0, context -> {
            release.await(5, TimeUnit.SECONDS);
            return "done";
        });
        assertTrue(handle.waitFor(100).isEmpty());
        JobView still = handle.view().orElseThrow();
        assertEquals(JobState.RUNNING, still.state());
        release.countDown();
    }

    @Test
    void completedViewsAreTrimmedToRetention() throws Exception {
        manager.shutdown();
        manager = new JobManager(3);
        for (int i = 0; i < 6; i++) {
            JobHandle handle = manager.submit("test.trim-" + i, Optional.empty(), 0, context -> "r");
            handle.waitFor(5000);
        }
        int retained = 0;
        for (int i = 0; i < 6; i++) {
            if (manager.view("job-" + (i + 1)).isPresent()) {
                retained++;
            }
        }
        assertEquals(3, retained);
    }

    @Test
    void shutdownCancelsOutstandingWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        JobHandle handle = manager.submit("test.shutdown", Optional.empty(), 0, context -> {
            release.await(5, TimeUnit.SECONDS);
            return "done";
        });
        manager.shutdown();
        JobView view = handle.waitFor(2000).orElseThrow();
        assertEquals(JobState.CANCELLED, view.state());
        assertTrue(manager.isShutdown());
        assertTrue(manager.view(handle.id()).isPresent());
        release.countDown();
    }

    private static JobView awaitState(JobHandle handle, JobState target) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < until) {
            JobView view = handle.view().orElseThrow();
            if (view.state() == target) {
                return view;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job never reached state " + target);
    }
}
