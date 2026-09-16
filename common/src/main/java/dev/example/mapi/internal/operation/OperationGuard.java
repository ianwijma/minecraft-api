package dev.example.mapi.internal.operation;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Enforces the authorization rules of {@code docs/product-spec.md} §14
 * against an operation's declared metadata:
 *
 * <ol>
 *   <li>the operation's normal scopes must be granted;</li>
 *   <li>destructive operations additionally require the
 *       {@code operations:destructive} grant;</li>
 *   <li>destructive operations additionally require explicit request intent
 *       from the caller. A request flag alone never grants permission, and a
 *       grant alone never replaces intent. Matching target/world identity is
 *       checked by the world-scoped handlers themselves (plan chunk 2.2).
 * </ol>
 */
public final class OperationGuard {

    /**
     * @param op                the operation being called, never {@code null}
     * @param grantedScopes     scopes granted to the caller, never
     *                          {@code null}
     * @param destructiveIntent true when the request explicitly confirms the
     *                          destructive action
     * @throws ProblemException when access must be refused
     */
    public void checkAccess(OperationDescriptor op, Set<Scope> grantedScopes, boolean destructiveIntent) {
        Set<Scope> granted = java.util.Objects.requireNonNull(grantedScopes, "grantedScopes");
        Set<Scope> missing = new TreeSet<>();
        for (Scope required : op.requiredScopes()) {
            if (!granted.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE, "missing required scopes",
                    Map.of("operation", op.id(),
                            "missing", missing.stream().map(Scope::wireName).toList()));
        }
        if (op.destructive() && !granted.contains(Scope.OPERATIONS_DESTRUCTIVE)) {
            throw new ProblemException(ProblemCode.INSUFFICIENT_SCOPE,
                    "destructive operations require the operations:destructive grant",
                    Map.of("operation", op.id(), "required", Scope.OPERATIONS_DESTRUCTIVE.wireName()));
        }
        if (op.destructive() && !destructiveIntent) {
            throw new ProblemException(ProblemCode.DESTRUCTIVE_INTENT_REQUIRED,
                    "destructive request requires explicit intent",
                    Map.of("operation", op.id()));
        }
    }

    /**
     * Convenience overload that also verifies the caller-selected execution
     * mode is supported (spec §3.3: unsupported modes are rejected, never
     * silently replaced).
     *
     * @param op                the operation being called
     * @param grantedScopes     scopes granted to the caller
     * @param destructiveIntent true when the request explicitly confirms a
     *                          destructive action
     * @param requestedMode     the caller-selected execution mode, or
     *                          {@code null} for non-action operations
     * @throws ProblemException when access must be refused or the mode is
     *     unsupported
     */
    public void checkAccess(
            OperationDescriptor op, Set<Scope> grantedScopes, boolean destructiveIntent,
            ExecutionMode requestedMode) {
        checkAccess(op, grantedScopes, destructiveIntent);
        if (requestedMode != null && !op.supportsMode(requestedMode)) {
            var supported = new LinkedHashSet<String>();
            for (ExecutionMode mode : op.supportedExecutionModes()) {
                supported.add(mode.wireName());
            }
            throw new ProblemException(ProblemCode.EXECUTION_MODE_UNSUPPORTED,
                    "execution mode not supported by this operation",
                    Map.of("operation", op.id(),
                            "requested", requestedMode.wireName(),
                            "supported", supported));
        }
    }
}
