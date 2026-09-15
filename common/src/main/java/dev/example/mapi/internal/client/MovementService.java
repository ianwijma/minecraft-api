package dev.example.mapi.internal.client;

import dev.example.mapi.internal.action.ActionReceipt;
import dev.example.mapi.internal.clock.ClockId;
import dev.example.mapi.internal.operation.ExecutionMode;
import dev.example.mapi.internal.operation.OperationDescriptor;
import dev.example.mapi.internal.operation.OperationGuard;
import dev.example.mapi.internal.operation.OperationRegistry;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.operation.SideEffectClass;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Movement primitives (spec §16: straight-line movement, required mod).
 * Waypoints are executed as bounded straight-line holds: bearing as
 * yaw/pitch deltas applied through the raw mouse path, forward hold for the
 * requested client ticks. Teleportation is never substituted (spec §3.3).
 *
 * <p>Receipts (§3.4) keep the three outcome layers distinct; the movement
 * primitive reports dispatched boundaries, not final positions — position
 * verification is the caller's wait/assert step (runner side).
 */
public final class MovementService {

    /** One waypoint: look direction (degrees, yaw around Y, pitch) + move time. */
    public record Waypoint(double yaw, double pitch, int ticks) {

        public Waypoint {
            if (ticks < 1 || ticks > 3600) {
                throw new IllegalArgumentException("ticks must be 1..3600");
            }
            if (!Double.isFinite(yaw) || !Double.isFinite(pitch)) {
                throw new IllegalArgumentException("yaw/pitch must be finite");
            }
            if (pitch < -90 || pitch > 90) {
                throw new IllegalArgumentException("pitch must be -90..90");
            }
        }
    }

    /** A completed waypoint leg. */
    public record LegResult(int index, double yaw, double pitch, int requestedTicks,
            int heldTicks, long startBoundary, long endBoundary) {

        /** @return the leg as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("yaw", yaw);
            map.put("pitch", pitch);
            map.put("requestedTicks", requestedTicks);
            map.put("heldTicks", heldTicks);
            map.put("startBoundary", startBoundary);
            map.put("endBoundary", endBoundary);
            return map;
        }
    }

    /** Raw-input surface the service drives (implemented over the backend). */
    public interface RawInput {

        void lookDelta(double yawDelta, double pitchDelta);

        void forward(boolean held);

        long clientTick();
    }

    private final ClientBridge bridge;
    private final OperationRegistry operations;
    private final OperationGuard guard;
    private final InputScheduler scheduler;

    /** @param tickSource the client-tick counter, never {@code null} */
    public MovementService(ClientBridge bridge, OperationRegistry operations,
            OperationGuard guard, LongSupplier tickSource) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.scheduler = new InputScheduler(Objects.requireNonNull(tickSource, "tickSource"));
        operations.register(new OperationDescriptor(
                "client.movement.waypoints",
                "Execute straight-line waypoints (raw-input; no teleport fallback)",
                java.util.Set.of(), false, SideEffectClass.LOCAL, true,
                java.util.Set.of(ExecutionMode.RAW_INPUT)));
    }

    /**
     * Executes waypoints in order.
     *
     * @param waypoints    ordered legs, 1..64
     * @param grantedScopes caller scopes
     * @param deadlineEpochMs wall-clock deadline for the whole path
     * @return the receipt plus per-leg results as an ordered map
     */
    public Map<String, Object> executeWaypoints(java.util.List<Waypoint> waypoints,
            java.util.Set<Scope> grantedScopes, long deadlineEpochMs) throws Exception {
        Objects.requireNonNull(waypoints, "waypoints");
        if (waypoints.isEmpty() || waypoints.size() > 64) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "waypoints must be 1..64");
        }
        var descriptor = operations.find("client.movement.waypoints").orElseThrow();
        var backend = bridge.input().orElseThrow(() -> new ProblemException(
                ProblemCode.CAPABILITY_UNAVAILABLE, "input backend unavailable"));
        // The scheduling loop runs on the CALLING thread (it sleeps while
        // client ticks advance on the client thread); each raw dispatch is
        // bounced onto the client thread individually. Wrapping the whole
        // loop in a client-thread task would deadlock against the tick
        // counter (spec §4.2 boundary semantics).
        RawInput raw = new RawInput() {
            @Override
            public void lookDelta(double yawDelta, double pitchDelta) {
                try {
                    bridge.onClientThread(() -> {
                        backend.mouseDelta(yawDelta, pitchDelta);
                        return null;
                    });
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void forward(boolean held) {
                try {
                    bridge.onClientThread(() -> {
                        // W (GLFW_KEY_W) through the raw key path.
                        if (held) {
                            backend.pressKey(87);
                        } else {
                            backend.releaseKey(87);
                        }
                        return null;
                    });
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public long clientTick() {
                return backend.clientTick();
            }
        };
        long deadline = deadlineEpochMs > 0 ? deadlineEpochMs
                : System.currentTimeMillis() + 30_000;

        try {
            guard.checkAccess(descriptor, grantedScopes, false, ExecutionMode.RAW_INPUT);
        } catch (ProblemException e) {
            ActionReceipt receipt = ActionReceipt.builder(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), ExecutionMode.RAW_INPUT)
                    .backendId(backend.backendId())
                    .backendVersion(backend.backendVersion())
                    .dispatchOutcome(ActionReceipt.DispatchOutcome.REJECTED_PRE_DISPATCH)
                    .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                    .note(e.getMessage())
                    .build();
            Map<String, Object> details = new LinkedHashMap<>(e.details());
            details.put("receipt", receipt.toMap());
            throw new ProblemException(e.code(), e.getMessage(), details);
        }

        java.util.List<LegResult> legs = new java.util.ArrayList<>();
        ActionReceipt receipt;
        try {
            for (int i = 0; i < waypoints.size(); i++) {
                Waypoint waypoint = waypoints.get(i);
                raw.lookDelta(waypoint.yaw(), waypoint.pitch());
                InputScheduler.HoldResult hold = scheduler.holdKey(
                        new InputScheduler.Dispatch() {
                            @Override
                            public void down(int keyCode) {
                                raw.forward(true);
                            }

                            @Override
                            public void up(int keyCode) {
                                raw.forward(false);
                            }
                        },
                        87, waypoint.ticks(), deadline);
                legs.add(new LegResult(i, waypoint.yaw(), waypoint.pitch(),
                        waypoint.ticks(), hold.heldTicks(),
                        hold.startBoundary(), hold.endBoundary()));
                if (System.currentTimeMillis() >= deadline) {
                    throw new ProblemException(ProblemCode.DEADLINE_EXCEEDED,
                            "waypoint path exceeded the wall-clock deadline");
                }
            }
            receipt = ActionReceipt.builder(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), ExecutionMode.RAW_INPUT)
                    .backendId(backend.backendId())
                    .backendVersion(backend.backendVersion())
                    .clock(ClockId.CLIENT_TICK)
                    .startBoundary(legs.isEmpty() ? null : legs.get(0).startBoundary())
                    .endBoundary(legs.isEmpty() ? null : legs.get(legs.size() - 1).endBoundary())
                    .dispatchOutcome(ActionReceipt.DispatchOutcome.DISPATCHED)
                    .clientStateChanged(Boolean.TRUE)
                    .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                    .build();
        } catch (ProblemException e) {
            receipt = ActionReceipt.builder(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), ExecutionMode.RAW_INPUT)
                    .backendId(backend.backendId())
                    .backendVersion(backend.backendVersion())
                    .dispatchOutcome(ActionReceipt.DispatchOutcome.CANCELLED)
                    .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                    .note(e.getMessage())
                    .build();
            Map<String, Object> details = new LinkedHashMap<>(e.details());
            details.put("receipt", receipt.toMap());
            details.put("completedLegs", legs.size());
            throw new ProblemException(e.code(), e.getMessage(), details);
        }

        Map<String, Object> out = receipt.toMap();
        out.put("legs", legs.stream().map(LegResult::toMap).toList());
        out.put("completedLegs", legs.size());
        return out;
    }
}
