package dev.example.mapi.internal.capability;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Static capability metadata for one operation (spec §5): the scope that
 * authorizes its effect, its stability tier, and the strength of its
 * coverage guarantee. Dynamic state (enabled/available/authorized) is
 * evaluated per request by {@code HttpApiServer}.
 *
 * @param op        dotted operation id
 * @param scope     scope required for the effect (§4.2)
 * @param tier      core | extended | experimental
 * @param coverage  full | partial | adapter-backed | best-effort
 * @param enableGuard optional switch predicate ({@code null} = always on)
 * @param available predicate over runtime state ({@code null} = always)
 */
public record CapabilityEntry(
        String op,
        String scope,
        String tier,
        String coverage,
        Predicate<Capabilities> enabled,
        Predicate<Capabilities> available) {

    /** Tier values. */
    public static final String CORE = "core";
    public static final String EXTENDED = "extended";
    public static final String EXPERIMENTAL = "experimental";

    /** Coverage values (spec §5). */
    public static final String FULL = "full";
    public static final String ADAPTER_BACKED = "adapter-backed";
    public static final String BEST_EFFORT = "best-effort";

    /**
     * Dynamic state inputs shared by the predicates.
     */
    public record Capabilities(boolean serverRunning, boolean clientOpsRegistered,
            boolean reflectionEnabled, boolean filesEnabled) {
    }

    private static final Predicate<Capabilities> ALWAYS = any -> true;

    /**
     * The implemented operation surface. Keep in sync with the routes and
     * {@code docs/CAPABILITIES.md}; {@code ContractSyncTest} guards routes.
     */
    public static final List<CapabilityEntry> TABLE = List.of(
            // -- core: common (§6.1) --
            entry("info", "observe", CORE, "full"),
            entry("live", "observe", CORE, "full"),
            entry("ready", "observe", CORE, "full"),
            entry("time", "observe", CORE, "full"),
            entry("capabilities", "observe", CORE, "full"),
            entry("mods.read", "observe", CORE, "full"),
            entry("registry.read", "observe", CORE, "adapter-backed"),
            entry("tags.read", "observe", CORE, "adapter-backed"),
            entry("tasks.create", "observe", CORE, "full"),
            entry("tasks.read", "observe", CORE, "full"),
            entry("tasks.cancel", "observe", CORE, "full"),
            entry("events.poll", "observe", CORE, "full"),
            entry("events.stream", "observe", CORE, "full"),
            entry("leases.acquire", "observe", CORE, "full"),
            entry("leases.manage", "observe", CORE, "full"),
            entry("logs.read", "diagnostics", CORE, "full"),
            entry("crash-reports.read", "diagnostics", CORE, "full"),
            // -- core: server (§6.2) --
            entry("server.status", "observe", CORE, "adapter-backed"),
            entry("server.players", "observe", CORE, "adapter-backed"),
            entry("server.commands.execute", "commands.execute", CORE, "adapter-backed",
                    Capabilities::serverRunning),
            entry("server.world.block", "world.read", CORE, "adapter-backed",
                    Capabilities::serverRunning),
            entry("server.world.block-entity", "world.read", CORE, "adapter-backed",
                    Capabilities::serverRunning),
            entry("server.world.storage", "world.read", CORE, "adapter-backed",
                    Capabilities::serverRunning),
            entry("server.world.time", "world.read", CORE, "adapter-backed",
                    Capabilities::serverRunning),
            // -- core: client (§6.3) --
            entry("client.status", "client.control", CORE, "full", availableClient()),
            entry("client.screen.tree", "client.control", CORE, "best-effort", availableClient()),
            entry("client.input.key", "client.control", CORE, "adapter-backed", availableClient()),
            entry("client.screenshot", "client.control", "extended", "adapter-backed",
                    availableClient()),
            // -- extended (§6.1/§6.2) --
            entry("diagnostics.threads", "diagnostics", "extended", "full"),
            entry("diagnostics.memory-gc", "diagnostics", "extended", "full"),
            entry("files.read", "files.read", "extended", "full",
                    cap -> cap.filesEnabled(), ALWAYS),
            entry("files.write", "files.write", "extended", "full",
                    cap -> cap.filesEnabled(), ALWAYS),
            entry("ext.spi", "observe", "extended", "adapter-backed"),
            // -- experimental (§6.1) --
            entry("unsafe.reflect", "unsafe.execute", "experimental", "full",
                    cap -> cap.reflectionEnabled(), ALWAYS),
            entry("unsafe.invoke", "unsafe.execute", "experimental", "full",
                    cap -> cap.reflectionEnabled(), ALWAYS));

    private static CapabilityEntry entry(String op, String scope, String tier, String coverage) {
        return new CapabilityEntry(op, scope, tier, coverage, ALWAYS, ALWAYS);
    }

    private static CapabilityEntry entry(String op, String scope, String tier, String coverage,
            Predicate<Capabilities> available) {
        return new CapabilityEntry(op, scope, tier, coverage, ALWAYS, available);
    }

    private static CapabilityEntry entry(String op, String scope, String tier, String coverage,
            Predicate<Capabilities> enabled, Predicate<Capabilities> available) {
        return new CapabilityEntry(op, scope, tier, coverage, enabled, available);
    }

    private static Predicate<Capabilities> availableClient() {
        return Capabilities::clientOpsRegistered;
    }

    /**
     * @return the wire form of one entry with the dynamic state applied, in
     *         the documented key order
     */
    public Map<String, Object> toJson(Capabilities state, boolean authorized) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("op", op);
        out.put("supported", true);
        out.put("enabled", enabled.test(state));
        out.put("available", available.test(state));
        out.put("authorized", authorized);
        out.put("coverage", coverage);
        out.put("tier", tier);
        return out;
    }
}
