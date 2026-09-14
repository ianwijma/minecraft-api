package dev.example.mapi.internal.client;

import dev.example.mapi.internal.action.ActionReceipt;
import dev.example.mapi.internal.operation.ExecutionMode;
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

/**
 * Client action dispatch (spec §3.1–§3.4): every action declares and reports
 * its execution mode; unsupported modes fail with
 * {@code EXECUTION_MODE_UNSUPPORTED} — there is no silent fallback
 * (structurally enforced by {@link ActionReceipt}, whose actual mode must
 * equal the requested mode). Receipts keep the three outcome layers distinct.
 */
public final class ActionDispatchService {

    private final ClientBridge bridge;
    private final OperationRegistry operations;
    private final OperationGuard guard;
    private final InputScheduler scheduler;

    /** A dispatched action request. */
    public record ActionRequest(String kind, ExecutionMode requestedMode,
            int keyCode, int ticks, long deadlineEpochMs) {
    }

    /**
     * @param bridge     the client bridge, never {@code null}
     * @param operations operation registry (client action descriptors are
     *                   registered here)
     * @param guard      authorization guard
     */
    public ActionDispatchService(ClientBridge bridge, OperationRegistry operations,
            OperationGuard guard) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.guard = Objects.requireNonNull(guard, "guard");
        var input = bridge.input().orElseThrow(() -> new IllegalArgumentException(
                "action dispatch requires an input backend"));
        this.scheduler = new InputScheduler(input::clientTick);
        operations.register(new dev.example.mapi.internal.operation.OperationDescriptor(
                "client.actions.hold-key", "Hold a key for N client ticks (raw-input)",
                java.util.Set.of(), false, SideEffectClass.LOCAL, true,
                java.util.Set.of(ExecutionMode.RAW_INPUT)));
    }

    /**
     * Dispatches a hold-key action in raw-input mode and produces the receipt.
     *
     * @param request        the action request, never {@code null}
     * @param grantedScopes  caller scopes
     * @return the receipt as an ordered map
     */
    public Map<String, Object> holdKey(ActionRequest request,
            java.util.Set<Scope> grantedScopes) throws Exception {
        Objects.requireNonNull(request, "request");
        var descriptor = operations.find("client.actions.hold-key").orElseThrow();
        var input = bridge.input().orElseThrow(() -> new ProblemException(
                ProblemCode.CAPABILITY_UNAVAILABLE, "input backend unavailable"));
        long deadline = request.deadlineEpochMs() > 0
                ? request.deadlineEpochMs()
                : System.currentTimeMillis() + 10_000;
        ClientBridge.InputBackend.Coverage coverage = input.coverage();
        try {
            guard.checkAccess(descriptor, grantedScopes, false, request.requestedMode());
        } catch (ProblemException e) {
            // The rejection still produces a receipt: requested mode is kept,
            // dispatch outcome is rejected-pre-dispatch (no silent fallback).
            ActionReceipt receipt = ActionReceipt.builder(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), request.requestedMode())
                    .actualMode(request.requestedMode())
                    .backendId(input.backendId())
                    .backendVersion(input.backendVersion())
                    .dispatchOutcome(ActionReceipt.DispatchOutcome.REJECTED_PRE_DISPATCH)
                    .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                    .note(e.getMessage())
                    .build();
            Map<String, Object> details = new LinkedHashMap<>(e.details());
            details.put("receipt", receipt.toMap());
            throw new ProblemException(e.code(), e.getMessage(), details);
        }
        ActionReceipt receipt;
        try {
            InputScheduler.HoldResult result = bridge.onClientThread(() -> {
                try {
                    return scheduler.holdKey(new InputScheduler.Dispatch() {
                        @Override
                        public void down(int keyCode) {
                            input.pressKey(keyCode);
                        }

                        @Override
                        public void up(int keyCode) {
                            input.releaseKey(keyCode);
                        }
                    }, request.keyCode(), request.ticks(), deadline);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProblemException(ProblemCode.SERVER_BUSY, "interrupted during hold");
                }
            });
            receipt = ActionReceipt.builder(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), ExecutionMode.RAW_INPUT)
                    .backendId(input.backendId())
                    .backendVersion(input.backendVersion())
                    .clock(dev.example.mapi.internal.clock.ClockId.CLIENT_TICK)
                    .startBoundary(result.startBoundary())
                    .endBoundary(result.endBoundary())
                    .dispatchOutcome(ActionReceipt.DispatchOutcome.DISPATCHED)
                    .clientStateChanged(Boolean.TRUE)
                    .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                    .build();
        } catch (ProblemException e) {
            receipt = ActionReceipt.builder(UUID.randomUUID().toString(),
                            UUID.randomUUID().toString(), ExecutionMode.RAW_INPUT)
                    .backendId(input.backendId())
                    .backendVersion(input.backendVersion())
                    .dispatchOutcome(ActionReceipt.DispatchOutcome.CANCELLED)
                    .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                    .note(e.getMessage())
                    .build();
            // The failure itself still surfaces to the caller.
            throw new ProblemException(e.code(), e.getMessage(), receiptToDetails(receipt, coverage, e));
        }
        Map<String, Object> out = receipt.toMap();
        out.put("heldTicks", request.ticks());
        out.put("coverage", java.util.Map.of(
                "callbackDispatch", coverage.callbackDispatch(),
                "keybindingState", coverage.keybindingState(),
                "helperPolling", coverage.helperPolling(),
                "screenDispatch", coverage.screenDispatch(),
                "unsupportedNativePolling", coverage.unsupportedNativePolling()));
        return out;
    }

    private static Map<String, Object> receiptToDetails(ActionReceipt receipt,
            ClientBridge.InputBackend.Coverage coverage, ProblemException cause) {
        Map<String, Object> details = new LinkedHashMap<>(cause.details());
        details.put("receipt", receipt.toMap());
        details.put("coverage", coverage.unsupportedNativePolling());
        return details;
    }
}
