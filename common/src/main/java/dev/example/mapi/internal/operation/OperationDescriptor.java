package dev.example.mapi.internal.operation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable metadata for one API operation (spec §14): the scopes it needs,
 * whether it is destructive, its side-effect class, whether it requires a
 * control lease, and which execution modes it supports. Every operation
 * endpoint publishes this metadata; the HTTP layer never hard-codes it.
 *
 * @param id                      stable operation identifier (route-oriented,
 *                                for example {@code "client.actions.press"}),
 *                                never blank
 * @param summary                 one-line human summary, never blank
 * @param requiredScopes          normal operation scopes (the separate
 *                                destructive/unrestricted grants are checked
 *                                by {@link OperationGuard}); unmodifiable,
 *                                possibly empty
 * @param destructive             true when the operation can destroy game or
 *                                world state and needs the destructive grant
 *                                plus explicit request intent
 * @param sideEffectClass         declared side-effect class, never
 *                                {@code null}
 * @param requiresLease           true when the operation needs ownership of
 *                                the relevant control lease
 * @param supportedExecutionModes supported modes; unmodifiable, possibly
 *                                empty for operations that are not actions
 */
public record OperationDescriptor(
        String id,
        String summary,
        Set<Scope> requiredScopes,
        boolean destructive,
        SideEffectClass sideEffectClass,
        boolean requiresLease,
        Set<ExecutionMode> supportedExecutionModes) {

    public OperationDescriptor {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("operation id must not be blank");
        }
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank()) {
            throw new IllegalArgumentException("operation summary must not be blank");
        }
        requiredScopes = Set.copyOf(Objects.requireNonNull(requiredScopes, "requiredScopes"));
        sideEffectClass = Objects.requireNonNull(sideEffectClass, "sideEffectClass");
        supportedExecutionModes =
                Set.copyOf(Objects.requireNonNull(supportedExecutionModes, "supportedExecutionModes"));
        if (sideEffectClass == SideEffectClass.UNRESTRICTED
                && !requiredScopes.contains(Scope.OPERATIONS_UNRESTRICTED)) {
            throw new IllegalArgumentException(
                    "unrestricted operations must require the operations:unrestricted scope");
        }
    }

    /** @return whether the caller-selected mode is supported by this operation */
    public boolean supportsMode(ExecutionMode mode) {
        return supportedExecutionModes.contains(Objects.requireNonNull(mode, "mode"));
    }

    /**
     * @return the descriptor as an ordered map for JSON serialization (wire
     *     names for scopes, side-effect class, and execution modes)
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("summary", summary);
        map.put("requiredScopes", requiredScopes.stream().map(Scope::wireName).sorted().toList());
        map.put("destructive", destructive);
        map.put("sideEffectClass", sideEffectClass.wireName());
        map.put("requiresLease", requiresLease);
        map.put("supportedExecutionModes",
                supportedExecutionModes.stream().map(ExecutionMode::wireName).sorted().toList());
        return map;
    }
}
