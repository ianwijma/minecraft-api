package dev.example.mapi.internal.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.clock.ClockId;
import dev.example.mapi.internal.encoding.Tag;
import dev.example.mapi.internal.encoding.TagType;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.operation.ExecutionMode;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActionReceiptTest {

    private static ActionReceipt.Builder full() {
        return ActionReceipt.builder("act-1", "req-1", ExecutionMode.RAW_INPUT)
                .backendId("mapi-default")
                .backendVersion("1")
                .clock(ClockId.CLIENT_TICK)
                .startBoundary(100L)
                .endBoundary(103L)
                .worldSessionId("world-1")
                .connectionSessionId("conn-1")
                .screenRevision(5L)
                .containerGeneration(2L)
                .dispatchOutcome(ActionReceipt.DispatchOutcome.DISPATCHED)
                .clientStateChanged(Boolean.TRUE)
                .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED);
    }

    @Test
    void receiptCarriesAllLayersDistinctly() {
        ActionReceipt receipt = full().build();
        Map<String, Object> map = receipt.toMap();
        assertEquals("dispatched", map.get("dispatchOutcome"));
        assertEquals(true, map.get("clientStateChanged"));
        assertEquals("not-requested", map.get("effectVerified"));
        assertEquals("raw-input", map.get("requestedMode"));
        assertEquals("raw-input", map.get("actualMode"));

        String json = JsonWriter.write(map);
        assertTrue(json.contains("\"startBoundary\":100"));
        assertTrue(json.contains("\"endBoundary\":103"));
        assertTrue(json.contains("\"clock\":\"client-tick\""));
        assertTrue(json.contains("\"screenRevision\":5"));
    }

    @Test
    void confirmedVerificationRequiresEvidence() {
        ActionReceipt.Builder builder = full().effectVerified(
                ActionReceipt.EffectVerification.CONFIRMED);
        assertThrows(IllegalArgumentException.class, builder::build);

        ActionReceipt verified = builder.verificationEvidence(
                new Tag.CompoundTag(Map.of("block", new Tag.StringTag("stone")))).build();
        String json = JsonWriter.write(verified.toMap());
        assertTrue(json.contains("\"effectVerified\":\"confirmed\""));
        assertTrue(json.contains("\"verificationEvidence\""));
    }

    @Test
    void partialAndCancelledRequireNotes() {
        assertThrows(IllegalArgumentException.class, () -> full()
                .dispatchOutcome(ActionReceipt.DispatchOutcome.PARTIAL).build());
        ActionReceipt partial = full()
                .dispatchOutcome(ActionReceipt.DispatchOutcome.PARTIAL)
                .note("released early at world unload")
                .build();
        assertEquals("partial", partial.toMap().get("dispatchOutcome"));
        assertTrue(JsonWriter.write(partial.toMap()).contains("released early"));
    }

    @Test
    void silentFallbackIsStructurallyImpossible() {
        assertThrows(IllegalArgumentException.class, () -> ActionReceipt.builder(
                "act-2", "req-2", ExecutionMode.RAW_INPUT)
                .actualMode(ExecutionMode.CLIENT_LOGIC)
                .dispatchOutcome(ActionReceipt.DispatchOutcome.DISPATCHED)
                .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                .build());
    }

    @Test
    void nullsNormalizeToEmpty() {
        ActionReceipt minimal = ActionReceipt.builder("act-3", "req-3", ExecutionMode.PRIVILEGED)
                .dispatchOutcome(ActionReceipt.DispatchOutcome.REJECTED_PRE_DISPATCH)
                .note("mode unsupported")
                .effectVerified(ActionReceipt.EffectVerification.NOT_REQUESTED)
                .build();
        assertTrue(minimal.backendId().isEmpty());
        assertTrue(minimal.clock().isEmpty());
        assertTrue(minimal.clientStateChanged().isEmpty());
        assertTrue(minimal.worldSessionId().isEmpty());
    }

    @Test
    void wireNamesAreStable() {
        for (ActionReceipt.DispatchOutcome outcome : ActionReceipt.DispatchOutcome.values()) {
            assertTrue(outcome.wireName().matches("[a-z-]+"));
        }
        for (ActionReceipt.EffectVerification verification : ActionReceipt.EffectVerification.values()) {
            assertTrue(verification.wireName().matches("[a-z-]+"));
        }
    }
}
