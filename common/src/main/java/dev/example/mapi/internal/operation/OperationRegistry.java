package dev.example.mapi.internal.operation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry of {@link OperationDescriptor operation metadata}. Route
 * handlers look up their descriptor here; the registry is the single source
 * of truth for the security metadata each operation publishes (spec §14).
 */
public final class OperationRegistry {

    private final Map<String, OperationDescriptor> operations = new LinkedHashMap<>();

    /**
     * Registers an operation. Registration order is preserved for stable
     * wire listings.
     *
     * @param descriptor the operation metadata, never {@code null}
     * @throws IllegalArgumentException when an operation with the same id is
     *     already registered
     */
    public void register(OperationDescriptor descriptor) {
        var existing = operations.putIfAbsent(descriptor.id(), descriptor);
        if (existing != null) {
            throw new IllegalArgumentException("duplicate operation id: " + descriptor.id());
        }
    }

    /** @return the descriptor for {@code id}, if registered */
    public Optional<OperationDescriptor> find(String id) {
        return Optional.ofNullable(operations.get(id));
    }

    /** @return the descriptors in registration order, unmodifiable */
    public List<OperationDescriptor> all() {
        return List.copyOf(operations.values());
    }

    /**
     * @return the registry listing as an ordered map for JSON serialization
     */
    public Map<String, Object> toMap() {
        return Map.of("operations", operations.values().stream().map(OperationDescriptor::toMap).toList());
    }
}
