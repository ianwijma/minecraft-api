package dev.example.mapi.internal.encoding;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Item component view for the normalized DTO representation (spec §11.3).
 * The three lifecycle states are distinct and must never be conflated:
 * {@code ABSENT} (no entry), {@code DEFAULT_INHERITED} (default from the
 * item's registry definition), and {@code REMOVED} (explicitly removed by a
 * patch).
 */
public final class ItemComponents {

    private ItemComponents() {
    }

    /** Lifecycle state of one component entry in the view. */
    public enum State {
        PRESENT("present"),
        DEFAULT_INHERITED("default-inherited"),
        REMOVED("removed"),
        ABSENT("absent");

        private final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }

        /** @return the exact string used on the wire */
        public String wireName() {
            return wireName;
        }
    }

    /** Serialization fidelity of one component value. */
    public enum SerializationStatus {
        FULL("full"),
        PARTIAL("partial"),
        UNSUPPORTED("unsupported");

        private final String wireName;

        SerializationStatus(String wireName) {
            this.wireName = wireName;
        }

        /** @return the exact string used on the wire */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * One component entry.
     *
     * @param id              namespaced component id (for example
     *                        {@code minecraft:damage}), never blank
     * @param state           lifecycle state, never {@code null}
     * @param value           typed component value for PRESENT entries
     * @param serialization   serialization fidelity, never {@code null}
     */
    public record Component(String id, State state, Optional<Tag> value,
            SerializationStatus serialization) {

        public Component {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("component id must not be blank");
            }
            state = java.util.Objects.requireNonNull(state, "state");
            value = value == null ? Optional.empty() : value;
            serialization = java.util.Objects.requireNonNull(serialization, "serialization");
            if (state == State.PRESENT && value.isEmpty() && serialization != SerializationStatus.UNSUPPORTED) {
                throw new IllegalArgumentException(
                        "a present component without a value must be declared UNSUPPORTED");
            }
            if (state != State.PRESENT && value.isPresent()) {
                throw new IllegalArgumentException("only PRESENT components carry a value");
            }
        }

        /** @return the entry as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("state", state.wireName());
            value.ifPresent(tag -> map.put("value", TagJson.toWire(tag)));
            map.put("serialization", serialization.wireName());
            return map;
        }
    }

    /**
     * Effective component view of one item stack.
     *
     * @param registryId      namespaced item registry id, never blank
     * @param count           stack count, at least 1
     * @param components      component entries by namespaced id
     * @param registryContext registry identity required to decode values;
     *                        empty when the caller already shares the registry
     */
    public record ItemStackView(String registryId, int count,
            Map<String, Component> components, Optional<String> registryContext) {

        public ItemStackView {
            if (registryId == null || registryId.isBlank()) {
                throw new IllegalArgumentException("registryId must not be blank");
            }
            if (count < 1) {
                throw new IllegalArgumentException("count must be at least 1");
            }
            components = components == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(components));
            registryContext = registryContext == null ? Optional.empty() : registryContext;
        }

        /** @return the view as an ordered map for JSON serialization */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("registryId", registryId);
            map.put("count", count);
            Map<String, Object> componentMaps = new LinkedHashMap<>();
            components.forEach((id, component) -> componentMaps.put(id, component.toMap()));
            map.put("components", componentMaps);
            registryContext.ifPresent(value -> map.put("registryContext", value));
            return map;
        }
    }
}
