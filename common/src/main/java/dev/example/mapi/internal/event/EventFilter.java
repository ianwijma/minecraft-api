package dev.example.mapi.internal.event;

import java.util.Optional;
import java.util.Set;

/**
 * Declarative, bounded event filter (spec §13.2: filters are data, never
 * executable uploaded code).
 *
 * @param types          event types to match; empty matches all
 * @param worldSessionId restrict to one world when present
 */
public record EventFilter(Set<String> types, Optional<String> worldSessionId) {

    public EventFilter {
        types = Set.copyOf(types == null ? Set.of() : types);
        worldSessionId = worldSessionId == null ? Optional.empty() : worldSessionId;
    }

    /** @return a filter that matches every event */
    public static EventFilter any() {
        return new EventFilter(Set.of(), Optional.empty());
    }

    /** @return true when the event satisfies this filter */
    public boolean matches(Event event) {
        if (!types.isEmpty() && !types.contains(event.type())) {
            return false;
        }
        return worldSessionId.isEmpty()
                || event.worldSessionId().equals(worldSessionId);
    }
}
