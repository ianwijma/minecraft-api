package dev.example.mapi.internal.operation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the scopes granted to a caller for the presented bearer token
 * (spec §14). Grants are config-backed: {@code http.scopes} /
 * {@code MAPI_HTTP_SCOPES} lists the granted scopes; when unset the token
 * grants the full set, preserving historical behavior.
 */
public interface ScopeGrants {

    /**
     * @param token the presented bearer token, may be {@code null}
     * @return the granted scopes, never {@code null}; empty when the token is
     *     unknown
     */
    Set<Scope> scopesForToken(String token);

    /** @return grants that award the full scope set to any non-null token */
    static ScopeGrants all() {
        return token -> token == null
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.allOf(Scope.class));
    }

    /**
     * @param token the presented bearer token
     * @return grants that award {@code scopes} to exactly {@code token}
     */
    static ScopeGrants fixed(String token, Set<Scope> scopes) {
        Objects.requireNonNull(token, "token");
        Set<Scope> copy = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        return presented -> token.equals(presented) ? copy : Set.of();
    }
}
