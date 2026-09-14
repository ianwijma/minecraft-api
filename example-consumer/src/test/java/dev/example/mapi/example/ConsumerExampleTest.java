package dev.example.mapi.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.example.mapi.api.MapiApi;
import dev.example.mapi.api.ServerStatusSnapshot;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verifies the documented API contract that matters most to consumers:
 * before bootstrap, the facade is empty and {@code require()} fails loudly.
 * This test intentionally does not launch Minecraft.
 */
class ConsumerExampleTest {

    @Test
    void beforeBootstrapApiIsUnavailable() {
        assertTrue(MapiApi.get().isEmpty());
        assertTrue(ConsumerExample.serverStatus().isEmpty());
        assertTrue(ConsumerExample.platformId().isEmpty());
        assertEquals("MAPI not initialized", ConsumerExample.statusLine());
        assertThrows(IllegalStateException.class, MapiApi::require);
        assertThrows(IllegalStateException.class, ConsumerExample::registerExampleService);
        assertThrows(IllegalStateException.class, ConsumerExample::registerExampleHttpExtension);
    }

    @Test
    void snapshotNormalization() {
        ServerStatusSnapshot raw = new ServerStatusSnapshot(-5L, 1000L, -1, -7, -3, Double.NaN, null);
        assertTrue(raw.capturedAtEpochMs() >= 0);
        assertSame("", raw.motd());
        assertTrue(raw.playerCount() >= 0);
        assertTrue(raw.maxPlayers() >= 0);
        assertTrue(raw.tickCount() >= 0);
        assertTrue(raw.averageTickTimeMs() >= 0);
        assertTrue(raw.uptimeMs() >= 0);
        Optional<ServerStatusSnapshot> optional = Optional.of(raw);
        assertEquals(optional, Optional.of(new ServerStatusSnapshot(raw.capturedAtEpochMs(), 1000L, 0, 0, 0, 0.0, "")));
    }
}
