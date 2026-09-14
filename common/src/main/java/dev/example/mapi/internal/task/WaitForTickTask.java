package dev.example.mapi.internal.task;

import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.SnapshotResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * First slow task kind (spec §6.2 "Testing: wait-for"): succeeds when the
 * server's tick counter reaches a target. Demonstrates the task protocol
 * end to end — progress, wall-time deadline, cooperative cancellation, and
 * owning-thread reads via bounded snapshots.
 */
public final class WaitForTickTask implements TaskManager.TaskKind {

    /** Default poll interval on the task thread. */
    public static final long POLL_INTERVAL_MS = 100;

    private final MapiRuntime runtime;

    /**
     * @param runtime runtime providing bounded server-thread snapshots
     */
    public WaitForTickTask(MapiRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public String kind() {
        return "wait-for-tick";
    }

    @Override
    public void execute(TaskManager.TaskContext context) throws Exception {
        Map<String, Object> payload = context.payload();
        Object rawTarget = payload.get("targetTick");
        long target = -1;
        if (rawTarget instanceof Long l) {
            target = l;
        } else if (rawTarget instanceof Double d && !d.isInfinite() && d == Math.floor(d)) {
            target = d.longValue();
        }
        if (target < 0) {
            context.failed("INVALID_PAYLOAD", "wait-for-tick requires an integer payload field "
                    + "'targetTick' >= 0 (the server tick to wait for).");
            return;
        }
        long startedAt = System.currentTimeMillis();
        long deadline = context.deadlineEpochMs();
        while (true) {
            context.checkCancelled();
            SnapshotResult result = runtime.trySnapshot();
            if (!result.serverRunning()) {
                context.failed("WRONG_STATE", "No active server session; wait-for-tick requires a running "
                        + "(dedicated or integrated) server.");
                return;
            }
            if (!result.timedOut() && result.snapshot() != null) {
                long tick = result.snapshot().tickCount();
                context.progress(tick, target, true);
                if (tick >= target) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("reachedTick", tick);
                    out.put("targetTick", target);
                    out.put("elapsedMs", System.currentTimeMillis() - startedAt);
                    context.succeeded(out);
                    return;
                }
            }
            if (System.currentTimeMillis() >= deadline) {
                context.checkCancelled();
                return;
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }
}
