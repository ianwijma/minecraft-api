package dev.example.mapi.internal.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.internal.config.MapiConfig;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScopeGrantsTest {

    private static final String TOKEN = "tok-0123456789abcdef";

    @Test
    void allGrantsEverythingNonNullAndNothingForNull() {
        ScopeGrants grants = ScopeGrants.all();
        assertEquals(Set.of(Scope.values()), grants.scopesForToken(TOKEN));
        assertEquals(Set.of(Scope.values()), grants.scopesForToken("any-other-token"));
        assertTrue(grants.scopesForToken(null).isEmpty());
    }

    @Test
    void fixedGrantsOnlyTheExactToken() {
        ScopeGrants grants = ScopeGrants.fixed(TOKEN, Set.of(Scope.SERVER_TICK_CONTROL));
        assertEquals(Set.of(Scope.SERVER_TICK_CONTROL), grants.scopesForToken(TOKEN));
        assertTrue(grants.scopesForToken("nope").isEmpty());
        assertTrue(grants.scopesForToken(null).isEmpty());
    }

    @Test
    void configBackedGrantsMatchGrantedScopes() {
        MapiConfig unrestricted = new MapiConfig(true, 20000, TOKEN, 60);
        ScopeGrants fromUnrestricted = unrestricted::grantedScopes;
        assertEquals(Set.of(Scope.values()), fromUnrestricted.scopesForToken(TOKEN));

        MapiConfig restricted = new MapiConfig(true, 20000, TOKEN, 60, Set.of(Scope.CLIENT_CONNECT));
        ScopeGrants fromRestricted = restricted::grantedScopes;
        assertEquals(Set.of(Scope.CLIENT_CONNECT), fromRestricted.scopesForToken(TOKEN));
        assertTrue(fromRestricted.scopesForToken("wrong").isEmpty());
    }
}
