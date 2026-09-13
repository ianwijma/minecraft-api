package dev.example.mapi.internal.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerBridgeTest {

    @Test
    void noneBridgeReportsNoCapabilities() {
        assertEquals("none", ServerBridge.NONE.bridgeId());
        assertTrue(ServerBridge.NONE.supportedCapabilities().isEmpty());
    }

    @Test
    void bridgesDeclareTheirCapabilitiesExplicitly() {
        ServerBridge fake = new ServerBridge() {
            @Override
            public String bridgeId() {
                return "test-bridge";
            }

            @Override
            public Set<String> supportedCapabilities() {
                return Set.of("world.lifecycle");
            }
        };
        assertEquals("test-bridge", fake.bridgeId());
        assertEquals(Set.of("world.lifecycle"), fake.supportedCapabilities());
    }
}
