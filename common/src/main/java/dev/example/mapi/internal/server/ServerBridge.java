package dev.example.mapi.internal.server;

import java.util.Set;

/**
 * Loader-neutral seam for server-side bridge capabilities (spec §15.1).
 * Implemented once per loader module and exposed through
 * {@code dev.example.mapi.internal.MapiPlatform#serverBridge()}; the common
 * code never compiles against Minecraft (ADR-0001/0002).
 *
 * <p>Capabilities are opt-in and declared explicitly: a capability absent
 * from {@link #supportedCapabilities()} is unsupported on this loader and
 * must never be advertised by any endpoint (spec §5, §15.3). New capability
 * accessors are added to this interface in the chunks that introduce them,
 * with default implementations that report unsupported so adapters opt in
 * one capability at a time.
 */
public interface ServerBridge {

    /** Bridge with no capabilities (default for platforms/tests). */
    ServerBridge NONE = new ServerBridge() {
        @Override
        public String bridgeId() {
            return "none";
        }

        @Override
        public Set<String> supportedCapabilities() {
            return Set.of();
        }
    };

    /**
     * @return stable bridge identifier (for reporting and diagnostics),
     *     never blank
     */
    String bridgeId();

    /**
     * @return capability identifiers supported by this bridge, never
     *     {@code null}; empty for the {@link #NONE} bridge. Capability ids
     *     are namespaced strings, for example {@code world.lifecycle}.
     */
    Set<String> supportedCapabilities();

    /**
     * @return the tick-control backend while a server is running and the
     *     loader implements tick control; empty otherwise (spec §5: absent
     *     support is explicit, never silent)
     */
    default java.util.Optional<dev.example.mapi.internal.tick.TickControlBackend> tickControl() {
        return java.util.Optional.empty();
    }
}
