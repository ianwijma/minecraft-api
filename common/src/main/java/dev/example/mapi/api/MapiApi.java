package dev.example.mapi.api;

import java.util.Optional;

/**
 * Static entry point to the public MAPI API.
 *
 * <p>MAPI binds exactly one {@link Mapi} instance during loader bootstrap.
 * Before that, {@link #get()} returns an empty optional and
 * {@link #require()} throws {@link IllegalStateException}.
 */
public final class MapiApi {

    private static volatile Mapi instance;

    private MapiApi() {
    }

    /**
     * @return the bound MAPI instance, or an empty optional before
     *         bootstrap
     */
    public static Optional<Mapi> get() {
        return Optional.ofNullable(instance);
    }

    /**
     * @return the bound MAPI instance
     * @throws IllegalStateException if MAPI has not been bootstrapped yet
     */
    public static Mapi require() {
        Mapi current = instance;
        if (current == null) {
            throw new IllegalStateException(
                    "MAPI is not initialized yet. Access it only after mod bootstrap "
                            + "(mod constructor / Fabric onInitialize); see docs/api.md.");
        }
        return current;
    }

    /**
     * Binds the singleton. Called by the MAPI bootstrap only; calling it from
     * other code is not supported.
     *
     * @param mapi the instance to bind, never {@code null}
     */
    public static void bind(Mapi mapi) {
        if (mapi == null) {
            throw new IllegalArgumentException("mapi must not be null");
        }
        if (instance != null && instance != mapi) {
            throw new IllegalStateException("MAPI is already initialized with a different instance");
        }
        instance = mapi;
    }
}
