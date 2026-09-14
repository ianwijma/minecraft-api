package dev.example.mapi.internal.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.operation.ExecutionMode;
import dev.example.mapi.internal.operation.OperationGuard;
import dev.example.mapi.internal.operation.OperationRegistry;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ActionDispatchServiceTest {

    /** Fake input backend with an advancing tick clock. */
    private static final class FakeInput implements ClientBridge.InputBackend {

        private final AtomicLong tick = new AtomicLong(500);
        private final AtomicLong frame = new AtomicLong(900);
        private final AtomicInteger pressed = new AtomicInteger();
        private final AtomicInteger released = new AtomicInteger();

        void advanceTicks(int n) {
            tick.addAndGet(n);
        }

        int pressed() {
            return pressed.get();
        }

        int released() {
            return released.get();
        }

        @Override
        public String backendId() {
            return "fake-input";
        }

        @Override
        public String backendVersion() {
            return "1";
        }

        @Override
        public Coverage coverage() {
            return new Coverage(true, true, true, true, List.of("native-glfw-polling"));
        }

        @Override
        public void pressKey(int keyCode) {
            pressed.incrementAndGet();
        }

        @Override
        public void releaseKey(int keyCode) {
            released.incrementAndGet();
        }

        @Override
        public void character(char c) {
        }

        @Override
        public void mouseDelta(double dx, double dy) {
        }

        @Override
        public long lastAppliedFrame() {
            return frame.get();
        }

        @Override
        public long clientTick() {
            return tick.get();
        }

        @Override
        public int keyCodeForMapping(String mappingId) {
            throw new UnsupportedOperationException(mappingId);
        }

        @Override
        public java.util.Optional<double[]> playerPosition() {
            return java.util.Optional.of(new double[] {1.0, 64.0, 2.0});
        }

        @Override
        public java.util.Optional<double[]> cameraOrientation() {
            return java.util.Optional.of(new double[] {90.0, 0.0});
        }
    }

    /** Bridge that runs tasks inline (single-threaded test). */
    private static final class InlineBridge implements ClientBridge {

        final FakeInput input = new FakeInput();

        @Override
        public String bridgeId() {
            return "test-client";
        }

        @Override
        public Set<String> supportedCapabilities() {
            return Set.of("client.input");
        }

        @Override
        public Optional<InputBackend> input() {
            return Optional.of(input);
        }

        @Override
        public Optional<ScreenshotBackend> screenshots() {
            return Optional.empty();
        }

        @Override
        public Optional<WindowBackend> window() {
            return Optional.empty();
        }

        @Override
        public Optional<LanBackend> lan() {
            return Optional.empty();
        }

        @Override
        public <T> T onClientThread(java.util.function.Supplier<T> task) {
            return task.get();
        }
    }

    @Test
    void holdKeyProducesADistinctLayeredReceipt() throws Exception {
        InlineBridge bridge = new InlineBridge();
        OperationRegistry registry = new OperationRegistry();
        ActionDispatchService service = new ActionDispatchService(
                bridge, registry, new OperationGuard());

        // Advance ticks while the hold runs.
        Thread ticker = new Thread(() -> {
            try {
                for (int i = 0; i < 10; i++) {
                    Thread.sleep(15);
                    bridge.input.advanceTicks(1);
                }
            } catch (InterruptedException ignored) {
                // teardown
            }
        });
        ticker.start();

        Map<String, Object> receipt = service.holdKey(new ActionDispatchService.ActionRequest(
                "hold-key", ExecutionMode.RAW_INPUT, 66, 3, System.currentTimeMillis() + 5000),
                Set.of());
        ticker.join();

        assertEquals("raw-input", receipt.get("requestedMode"));
        assertEquals("raw-input", receipt.get("actualMode"));
        assertEquals("dispatched", receipt.get("dispatchOutcome"));
        assertEquals(true, receipt.get("clientStateChanged"));
        assertEquals("not-requested", receipt.get("effectVerified"));
        assertEquals("fake-input", receipt.get("backendId"));
        assertEquals("client-tick", receipt.get("clock"));
        assertTrue(((Number) receipt.get("endBoundary")).longValue()
                >= ((Number) receipt.get("startBoundary")).longValue() + 3);
        assertEquals(1, bridge.input.pressed());
        assertEquals(1, bridge.input.released());

        @SuppressWarnings("unchecked")
        Map<String, Object> coverage = (Map<String, Object>) receipt.get("coverage");
        assertEquals(List.of("native-glfw-polling"), coverage.get("unsupportedNativePolling"));
    }

    @Test
    void unsupportedModeFailsWithReceiptAndNoFallback() throws Exception {
        InlineBridge bridge = new InlineBridge();
        ActionDispatchService service = new ActionDispatchService(
                bridge, new OperationRegistry(), new OperationGuard());

        ProblemException e = assertThrows(ProblemException.class, () -> service.holdKey(
                new ActionDispatchService.ActionRequest("hold-key", ExecutionMode.CLIENT_LOGIC,
                        66, 1, System.currentTimeMillis() + 100),
                Set.of()));
        assertEquals(ProblemCode.EXECUTION_MODE_UNSUPPORTED, e.code());
        assertEquals("client-logic", e.details().get("requested"));

        @SuppressWarnings("unchecked")
        Map<String, Object> receipt = (Map<String, Object>) e.details().get("receipt");
        // The receipt keeps the caller's requested mode verbatim - the
        // rejection happened pre-dispatch, and no mode was substituted.
        assertEquals("client-logic", receipt.get("requestedMode"));
        assertEquals("client-logic", receipt.get("actualMode"));
        assertEquals("rejected-pre-dispatch", receipt.get("dispatchOutcome"));
        assertTrue(receipt.get("note").toString().contains("supported"));
        assertEquals(0, bridge.input.pressed());
        assertEquals(0, bridge.input.released(), "no dispatch happened at all");
    }

    @Test
    void restrictedScopesDoNotBlockLeaselessRawInputByDefault() throws Exception {
        // Raw input is lease-gated at the HTTP layer; the substrate only
        // enforces the operation's own scopes (empty for hold-key).
        InlineBridge bridge = new InlineBridge();
        ActionDispatchService service = new ActionDispatchService(
                bridge, new OperationRegistry(), new OperationGuard());
        ProblemException grantedNarrowly = assertThrows(ProblemException.class,
                () -> service.holdKey(new ActionDispatchService.ActionRequest("hold-key",
                        ExecutionMode.RAW_INPUT, 66, 1, System.currentTimeMillis() + 100),
                        Set.of(Scope.CLIENT_SETTINGS)));
        // The failure is the deadline (no ticking thread), not a scope error.
        assertEquals(ProblemCode.DEADLINE_EXCEEDED, grantedNarrowly.code());
    }
}
