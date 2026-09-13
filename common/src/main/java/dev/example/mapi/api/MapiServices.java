package dev.example.mapi.api;

import java.util.Map;
import java.util.Optional;

/**
 * Loader-neutral registry for {@link MapiService} extensions.
 *
 * <p>Implementations are thread-safe: {@link #register} may be called from
 * any thread at any time after MAPI bootstrap. Duplicate ids are rejected.
 */
public interface MapiServices {

    /**
     * Registers a service under the given id.
     *
     * @param id      unique id, matching {@code [a-z][a-z0-9_-]{1,63}}
     * @param service the service instance; stored as-is, not copied
     * @return the same {@code service} instance, for fluent registration
     * @throws IllegalArgumentException if the id is {@code null}, blank, not
     *                                  matching the id format, or if the
     *                                  service is {@code null}
     * @throws IllegalStateException    if the id is already registered
     */
    MapiService register(String id, MapiService service);

    /**
     * @param id service id
     * @return the registered service, or an empty optional if no service with
     *         this id is registered
     */
    Optional<MapiService> get(String id);

    /**
     * @return an unmodifiable snapshot of all currently registered services,
     *         keyed by id; never {@code null}
     */
    Map<String, MapiService> all();
}
