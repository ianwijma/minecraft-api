package dev.example.mapi.internal.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.MapiRuntimeTest;
import dev.example.mapi.internal.MapiRuntimeTest.TestPlatform;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TaskManagerTest {

    private static final Logger LOG = LoggerFactory.getLogger(TaskManagerTest.class);

    private TestPlatform platform;
    private MapiRuntime runtime;

    private MapiRuntime runtime() {
        if (runtime == null) {
            platform = new TestPlatform(LOG);
            runtime = new MapiRuntime(platform);
        }
        return runtime;
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }

    private static boolean awaitState(AtomicReference<String> seen, String state) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (state.equals(seen.get())) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    void completesImmediateTaskWithResult() throws Exception {
        MapiRuntime rt = runtime();
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        TaskManager.TaskSnapshot snapshot = rt.taskManager().submit("wait-for-tick",
                Map.of("targetTick", 42L), null);
        assertTrue(snapshot.state().equals("queued") || snapshot.state().equals("running"),
                "submit must return a pre-terminal snapshot, got " + snapshot.state());
        AtomicReference<String> seen = new AtomicReference<>();
        TaskManager.TaskSnapshot done = pollUntil(snapshot.id(), seen, "succeeded");
        assertEquals("succeeded", done.state());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) done.result();
        assertEquals(42L, result.get("reachedTick"));
        assertEquals(42L, result.get("targetTick"));
        assertTrue((Long) result.get("elapsedMs") >= 0);
        assertNotNull(done.progress());
        assertEquals(42L, done.progress().get("units"));
        assertEquals(42L, done.progress().get("total"));
    }

    @Test
    void cancellationIsCooperativeAndKeepsPartialEffects() throws Exception {
        MapiRuntime rt = runtime();
        CountDownLatch started = new CountDownLatch(1);
        rt.taskManager().registerKind(new TaskManager.TaskKind() {
            @Override
            public String kind() {
                return "test.blocking";
            }

            @Override
            public void execute(TaskManager.TaskContext context) throws Exception {
                started.countDown();
                long deadline = context.deadlineEpochMs();
                while (System.currentTimeMillis() < deadline) {
                    context.checkCancelled();
                    context.recordPartialEffect(Map.of("effect", "tick-observed"));
                    Thread.sleep(20);
                }
                context.succeeded(Map.of());
            }
        });
        TaskManager.TaskSnapshot snapshot = rt.taskManager().submit("test.blocking", Map.of(), 30_000L);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        rt.taskManager().cancel(snapshot.id());
        AtomicReference<String> seen = new AtomicReference<>();
        TaskManager.TaskSnapshot cancelled = pollUntil(snapshot.id(), seen, "cancelled");
        assertEquals("cancelled", cancelled.state());
        assertTrue(cancelled.partialEffects().size() > 0,
                "cancellation must keep already-recorded partial effects");
    }

    @Test
    void deadlineExpiresTaskWithProtocolCode() throws Exception {
        MapiRuntime rt = runtime();
        rt.taskManager().registerKind(new TaskManager.TaskKind() {
            @Override
            public String kind() {
                return "test.slow";
            }

            @Override
            public void execute(TaskManager.TaskContext context) throws Exception {
                while (true) {
                    context.checkCancelled();
                    Thread.sleep(20);
                }
            }
        });
        TaskManager.TaskSnapshot snapshot = rt.taskManager().submit("test.slow", Map.of(), 150L);
        AtomicReference<String> seen = new AtomicReference<>();
        TaskManager.TaskSnapshot expired = pollUntil(snapshot.id(), seen, "expired");
        assertEquals("expired", expired.state());
        assertEquals("DEADLINE_EXCEEDED", expired.error().get("code"));
    }

    @Test
    void invalidPayloadFailsWithProtocolCode() throws Exception {
        MapiRuntime rt = runtime();
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        TaskManager.TaskSnapshot snapshot = rt.taskManager().submit("wait-for-tick", Map.of(), null);
        AtomicReference<String> seen = new AtomicReference<>();
        TaskManager.TaskSnapshot failed = pollUntil(snapshot.id(), seen, "failed");
        assertEquals("failed", failed.state());
        assertEquals("INVALID_PAYLOAD", failed.error().get("code"));
    }

    @Test
    void worldSessionEndFailsRunningTasksWithLifecycleChanged() throws Exception {
        MapiRuntime rt = runtime();
        CountDownLatch started = new CountDownLatch(1);
        rt.taskManager().registerKind(new TaskManager.TaskKind() {
            @Override
            public String kind() {
                return "test.hanging";
            }

            @Override
            public void execute(TaskManager.TaskContext context) throws Exception {
                started.countDown();
                while (true) {
                    context.checkCancelled();
                    Thread.sleep(20);
                }
            }
        });
        TaskManager.TaskSnapshot snapshot = rt.taskManager().submit("test.hanging", Map.of(), 30_000L);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        platform.lifecycleListener().onServerStopped();
        AtomicReference<String> seen = new AtomicReference<>();
        TaskManager.TaskSnapshot failed = pollUntil(snapshot.id(), seen, "failed");
        assertEquals("LIFECYCLE_CHANGED", failed.error().get("code"));
    }

    @Test
    void cancelTerminalTaskDoesNotChangeState() throws Exception {
        MapiRuntime rt = runtime();
        platform.lifecycleListener().onServerStarting(MapiRuntimeTest.TestServerHandle.inline());
        TaskManager.TaskSnapshot snapshot = rt.taskManager().submit("wait-for-tick",
                Map.of("targetTick", 42L), null);
        AtomicReference<String> seen = new AtomicReference<>();
        pollUntil(snapshot.id(), seen, "succeeded");
        TaskManager.TaskSnapshot after = rt.taskManager().cancel(snapshot.id());
        assertEquals("succeeded", after.state(), "cancel must not rewrite a terminal state");
    }

    private TaskManager.TaskSnapshot pollUntil(String id, AtomicReference<String> seen, String state)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        TaskManager.TaskSnapshot snapshot = null;
        while (System.currentTimeMillis() < deadline) {
            snapshot = runtime().taskManager().get(id);
            if (snapshot != null) {
                seen.set(snapshot.state());
                if (state.equals(snapshot.state())) {
                    return snapshot;
                }
            }
            Thread.sleep(20);
        }
        assertNotNull(snapshot, "task must exist");
        throw new AssertionError("task never reached state " + state + " (last: " + snapshot.state() + ")");
    }
}
