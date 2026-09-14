package dev.example.mapi.internal.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogCaptureServiceTest {

    @Test
    void recordsAboveThresholdOnly() {
        LogCaptureService logs = new LogCaptureService(16, List.of());
        logs.setThreshold(LogCaptureService.Level.INFO);
        logs.record(LogCaptureService.Level.DEBUG, "x", "hidden");
        logs.record(LogCaptureService.Level.INFO, "x", "seen");
        var entries = logs.entriesAfter(0, 100).entries();
        assertEquals(1, entries.size());
        assertEquals("seen", entries.get(0).message());
        assertEquals(LogCaptureService.Level.INFO, entries.get(0).level());
    }

    @Test
    void redactsConfiguredSecretsButKeepsShortWords() {
        LogCaptureService logs = new LogCaptureService(16, List.of("super-secret-token-value"));
        logs.record(LogCaptureService.Level.INFO, "mapi",
                "auth header was Bearer super-secret-token-value for user");
        logs.record(LogCaptureService.Level.INFO, "mapi", "user logged in");
        var entries = logs.entriesAfter(0, 100).entries();
        assertTrue(entries.get(0).message().contains("[redacted]"));
        assertTrue(!entries.get(0).message().contains("super-secret-token-value"));
        assertEquals("user logged in", entries.get(1).message());
    }

    @Test
    void shortSecretsAreNeverRedacted() {
        LogCaptureService logs = new LogCaptureService(16, List.of("a"));
        logs.record(LogCaptureService.Level.INFO, "mapi", "a plain sentence");
        assertEquals("a plain sentence", logs.entriesAfter(0, 10).entries().get(0).message());
    }

    @Test
    void ringEvictionProducesExplicitGaps() {
        LogCaptureService logs = new LogCaptureService(3, List.of());
        for (int i = 1; i <= 5; i++) {
            logs.record(LogCaptureService.Level.INFO, "mapi", "line-" + i);
        }
        assertEquals(5, logs.latestSeq());

        var fresh = logs.entriesAfter(0, 100);
        assertEquals(3, fresh.entries().size());
        assertTrue(!fresh.gap(), "cursor 0 is stream start, not a gap");
        assertEquals("line-3", fresh.entries().get(0).message());

        var gapped = logs.entriesAfter(1, 100);
        assertTrue(gapped.gap());
        assertEquals(2, gapped.droppedUpToSeq());
        assertEquals(5, gapped.newCursor());
    }

    @Test
    void boundsAreValidated() {
        LogCaptureService logs = new LogCaptureService(4, List.of());
        assertThrows(IllegalArgumentException.class, () -> logs.entriesAfter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> logs.entriesAfter(0, 1001));
        assertThrows(IllegalArgumentException.class, () -> new LogCaptureService(0, List.of()));
        assertThrows(NullPointerException.class, () -> logs.record(null, "x", "y"));
    }

    @Test
    void setSecretsIsAppliedToSubsequentEntries() {
        LogCaptureService logs = new LogCaptureService(8, List.of());
        logs.record(LogCaptureService.Level.INFO, "mapi", "token=before-secret");
        logs.setSecrets(List.of("before-secret"));
        logs.record(LogCaptureService.Level.INFO, "mapi", "token=before-secret");
        var entries = logs.entriesAfter(0, 10).entries();
        assertEquals("token=before-secret", entries.get(0).message(), "recorded before the secret was set");
        assertTrue(entries.get(1).message().contains("[redacted]"));
    }

    @Test
    void captureStartMetadataIsRecorded() {
        long before = System.currentTimeMillis() - 10;
        LogCaptureService logs = new LogCaptureService(8, List.of());
        assertTrue(logs.attachedAtEpochMs() >= before);
    }
}
