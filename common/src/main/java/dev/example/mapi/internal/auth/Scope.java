package dev.example.mapi.internal.auth;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The mandatory scope vocabulary (spec §4.2). Endpoints declare the scope
 * matching their <strong>effect</strong>, not their route shape; a token
 * lacking a required scope is rejected with 403 {@code FORBIDDEN_SCOPE}.
 *
 * <p>Optional constraints (instance, dimension, player, path prefix) arrive
 * with multi-token support; the Phase 1 model binds one scope set to the
 * single bootstrap token.
 */
public final class Scope {

    /** Read-only observations and metadata. */
    public static final String OBSERVE = "observe";

    /** Structured diagnostics (profiler, threads, GC). */
    public static final String DIAGNOSTICS = "diagnostics";

    /** Drive the local client (input, screens, camera). */
    public static final String CLIENT_CONTROL = "client.control";

    /** Read world state (blocks, entities, time). */
    public static final String WORLD_READ = "world.read";

    /** Mutate world state (Phase 1+: block set, fill). */
    public static final String WORLD_WRITE = "world.write";

    /** Execute commands as console/player (Phase 1+). */
    public static final String COMMANDS_EXECUTE = "commands.execute";

    /** Manage lifecycle (save, stop, gamerules). */
    public static final String LIFECYCLE_MANAGE = "lifecycle.manage";

    /** Sandboxed file reads (Phase 1+). */
    public static final String FILES_READ = "files.read";

    /** Sandboxed file writes (Phase 1+). */
    public static final String FILES_WRITE = "files.write";

    /** Trusted developer execution — runs with the game's privileges. */
    public static final String UNSAFE_EXECUTE = "unsafe.execute";

    /** All known scopes, in vocabulary order. */
    public static final Set<String> ALL = Set.of(
            OBSERVE, DIAGNOSTICS, CLIENT_CONTROL, WORLD_READ, WORLD_WRITE,
            COMMANDS_EXECUTE, LIFECYCLE_MANAGE, FILES_READ, FILES_WRITE, UNSAFE_EXECUTE);

    private Scope() {
    }

    /**
     * Parses a comma-separated scope list.
     *
     * @param csv scopes or {@code null}/blank for the full set
     * @return the validated scope set
     * @throws IllegalArgumentException on unknown or duplicate-free violations
     */
    public static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return ALL;
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String scope = raw.trim().toLowerCase(Locale.ROOT);
            if (scope.isEmpty()) {
                continue;
            }
            if (!ALL.contains(scope)) {
                throw new IllegalArgumentException(
                        "Unknown scope '" + scope + "'; known scopes: "
                                + String.join(", ", ALL.stream().sorted().toList()));
            }
            scopes.add(scope);
        }
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("http.scopes must name at least one scope");
        }
        return Set.copyOf(scopes);
    }
}
