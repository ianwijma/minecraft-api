package dev.example.mapi.internal.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperationRegistryTest {

    private static final OperationGuard GUARD = new OperationGuard();
    private static final Set<Scope> ALL = Set.of(Scope.values());

    private static OperationDescriptor readStatus() {
        return new OperationDescriptor("test.status.read", "Read server status",
                Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of());
    }

    private static OperationDescriptor tickStep() {
        return new OperationDescriptor("test.tick.step", "Step simulation ticks",
                Set.of(Scope.SERVER_TICK_CONTROL), false, SideEffectClass.GAME, true,
                Set.of(ExecutionMode.PRIVILEGED));
    }

    private static OperationDescriptor deleteWorld() {
        return new OperationDescriptor("test.world.delete", "Delete the world",
                Set.of(Scope.SERVER_TICK_CONTROL), true, SideEffectClass.GAME, true, Set.of());
    }

    @Test
    void registryRejectsDuplicatesAndPreservesOrder() {
        OperationRegistry registry = new OperationRegistry();
        registry.register(readStatus());
        registry.register(tickStep());
        assertEquals(List.of("test.status.read", "test.tick.step"),
                registry.all().stream().map(OperationDescriptor::id).toList());
        assertTrue(registry.find("test.tick.step").isPresent());
        assertTrue(registry.find("nope").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.register(readStatus()));
    }

    @Test
    void unrestrictedOperationsMustDeclareTheUnrestrictedScope() {
        assertThrows(IllegalArgumentException.class, () -> new OperationDescriptor(
                "test.cmd", "Run arbitrary command", Set.of(), true, SideEffectClass.UNRESTRICTED,
                false, Set.of()));
        OperationDescriptor ok = new OperationDescriptor("test.cmd", "Run arbitrary command",
                Set.of(Scope.OPERATIONS_UNRESTRICTED), true, SideEffectClass.UNRESTRICTED, false, Set.of());
        assertTrue(ok.requiredScopes().contains(Scope.OPERATIONS_UNRESTRICTED));
    }

    @Test
    void descriptorNormalizesAndRejectsBlanks() {
        assertThrows(NullPointerException.class, () -> new OperationDescriptor(
                null, "s", Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new OperationDescriptor(
                " ", "s", Set.of(), false, SideEffectClass.READ_ONLY, false, Set.of()));
        assertThrows(NullPointerException.class, () -> new OperationDescriptor(
                "id", "s", null, false, SideEffectClass.READ_ONLY, false, Set.of()));
        OperationDescriptor d = tickStep();
        assertThrows(UnsupportedOperationException.class, () -> d.requiredScopes().add(Scope.SERVER_PUBLISH));
        assertTrue(d.supportsMode(ExecutionMode.PRIVILEGED));
        assertTrue(!d.supportsMode(ExecutionMode.RAW_INPUT));
    }

    @Test
    void guardRequiresNormalScopes() {
        ProblemException e = assertThrows(ProblemException.class,
                () -> GUARD.checkAccess(tickStep(), Set.of(), false));
        assertEquals(ProblemCode.INSUFFICIENT_SCOPE, e.code());
        assertEquals(List.of("server:tick-control"), e.details().get("missing"));
        GUARD.checkAccess(tickStep(), ALL, false);
    }

    @Test
    void destructiveChainEnforcesGrantThenIntent() {
        OperationDescriptor op = deleteWorld();
        ProblemException noGrant = assertThrows(ProblemException.class,
                () -> GUARD.checkAccess(op, Set.of(Scope.SERVER_TICK_CONTROL), false));
        assertEquals(ProblemCode.INSUFFICIENT_SCOPE, noGrant.code());
        assertEquals("operations:destructive", noGrant.details().get("required"));

        ProblemException noIntent = assertThrows(ProblemException.class,
                () -> GUARD.checkAccess(op, ALL, false));
        assertEquals(ProblemCode.DESTRUCTIVE_INTENT_REQUIRED, noIntent.code());
        assertEquals(428, ProblemCode.DESTRUCTIVE_INTENT_REQUIRED.httpStatus());

        GUARD.checkAccess(op, ALL, true);
    }

    @Test
    void guardRejectsUnsupportedExecutionModesWithoutFallback() {
        ProblemException e = assertThrows(ProblemException.class,
                () -> GUARD.checkAccess(tickStep(), ALL, false, ExecutionMode.RAW_INPUT));
        assertEquals(ProblemCode.EXECUTION_MODE_UNSUPPORTED, e.code());
        assertEquals("raw-input", e.details().get("requested"));
        assertEquals(Set.of("privileged"),
                Set.copyOf((java.util.Collection<String>) e.details().get("supported")));

        GUARD.checkAccess(tickStep(), ALL, false, ExecutionMode.PRIVILEGED);
        GUARD.checkAccess(readStatus(), ALL, false, null);
    }

    @Test
    void wireMappingIsStable() {
        assertEquals("raw-input", ExecutionMode.RAW_INPUT.wireName());
        assertEquals("client-logic", ExecutionMode.CLIENT_LOGIC.wireName());
        assertEquals("privileged", ExecutionMode.PRIVILEGED.wireName());
        assertEquals("read-only", SideEffectClass.READ_ONLY.wireName());
        assertEquals("unrestricted", SideEffectClass.UNRESTRICTED.wireName());
        assertEquals("client:connect", Scope.CLIENT_CONNECT.wireName());
        assertEquals("operations:unrestricted", Scope.OPERATIONS_UNRESTRICTED.wireName());

        Map<String, Object> wire = tickStep().toMap();
        assertEquals("test.tick.step", wire.get("id"));
        assertEquals(List.of("server:tick-control"), wire.get("requiredScopes"));
        assertEquals("game", wire.get("sideEffectClass"));
        assertEquals(List.of("privileged"), wire.get("supportedExecutionModes"));
        assertEquals(true, wire.get("requiresLease"));
        assertEquals(false, wire.get("destructive"));
    }
}
