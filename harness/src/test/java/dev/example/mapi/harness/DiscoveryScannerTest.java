package dev.example.mapi.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DiscoveryScannerTest {

    private static final String VALID = """
            {"schemaVersion":1,"instanceId":"client-2","processSessionId":"0f1e2d3c",
             "pid":123,"startedAt":"2026-09-13T12:00:00Z","lastSeen":"2026-09-13T12:01:00Z",
             "readiness":"worldReady","physicalSide":"client","loader":"fabric","mcVersion":"26.2",
             "api":{"scheme":"http","host":"127.0.0.1","port":25586},
             "events":{"scheme":"ws","host":"127.0.0.1","port":25587},
             "labels":{"runId":"ci-482"},"unknownFutureField":true}
            """;

    @Test
    void parsesValidRecordIgnoringUnknownFields() {
        DiscoveryScanner.DiscoveryRecord record = DiscoveryScanner.parse(VALID);
        assertEquals("client-2", record.instanceId());
        assertEquals(123, record.pid());
        assertEquals("worldReady", record.readiness());
        assertEquals(25586, record.apiPort());
        assertEquals(25587, record.eventsPort());
    }

    @Test
    void rejectsMalformedAndInvalidContent() {
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class, () -> DiscoveryScanner.parse("{"));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class, () -> DiscoveryScanner.parse("[]"));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class,
                () -> DiscoveryScanner.parse(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class,
                () -> DiscoveryScanner.parse(VALID.replace("\"readiness\":\"worldReady\"",
                        "\"readiness\":\"bogus\"")));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class,
                () -> DiscoveryScanner.parse(VALID.replace("\"port\":25586", "\"port\":99999")));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class,
                () -> DiscoveryScanner.parse(VALID.replace("\"instanceId\":\"client-2\"",
                        "\"instanceId\":\"bad id!\"")));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class,
                () -> DiscoveryScanner.parse(VALID.replace(
                        "\"events\":{\"scheme\":\"ws\",\"host\":\"127.0.0.1\",\"port\":25587},",
                        "\"events\":{\"port\":\"bad\"},")));
        assertThrows(DiscoveryScanner.InvalidDiscoveryException.class, () -> DiscoveryScanner.parse(
                VALID.replace("\"pid\":123", "\"pid\":\"not-a-number\"")));
    }

    @Test
    void stalenessUsesHeartbeatAgeOnly() {
        DiscoveryScanner.DiscoveryRecord record = DiscoveryScanner.parse(VALID);
        long now = Instant.parse("2026-09-13T12:01:30Z").toEpochMilli();
        assertFalse(record.isStale(60_000, now), "30s heartbeat age must be fresh for a 60s budget");
        assertTrue(record.isStale(10_000, now), "30s heartbeat age must be stale for a 10s budget");
        DiscoveryScanner.DiscoveryRecord unreadable = DiscoveryScanner.parse(
                VALID.replace("\"lastSeen\":\"2026-09-13T12:01:00Z\"", "\"lastSeen\":\"garbage\""));
        assertTrue(unreadable.isStale(60_000, now), "unparseable heartbeat must count as stale");
    }

    @Test
    void waiterReturnsRecordWhenReadinessMatches() {
        DiscoveryScanner.DiscoveryRecord record = DiscoveryScanner.parse(VALID);
        Optional<DiscoveryScanner.DiscoveryRecord> hit = ReadinessWaiter.await(() -> Optional.of(record),
                "worldReady", 1000, 50);
        assertTrue(hit.isPresent());
        Optional<DiscoveryScanner.DiscoveryRecord> miss = ReadinessWaiter.await(() -> Optional.of(record),
                "clientJoined", 150, 50);
        assertFalse(miss.isPresent());
    }
}
