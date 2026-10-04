package dev.example.mapi.internal.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScreenshotFilesTest {

    @TempDir Path directory;
    private static final byte[] PNG = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };

    @Test
    void readerWaitsForPublicationWhenThePlaceholderIsAbsent() throws Exception {
        Path target = directory.resolve("capture.png");
        Path writing = directory.resolve("capture.png.writing");
        Files.write(writing, PNG);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> ScreenshotFiles.awaitPng(target,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5), 5, "capture timed out"));
            Thread.sleep(30);
            assertFalse(reading.isDone(), "a missing publication target is still pending");
            ScreenshotFiles.publish(writing, target);
            assertArrayEquals(PNG, reading.get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(writing));
        }
    }

    @Test
    void publicationReplacesAnEmptyPlaceholderWithTheCompletePng() throws Exception {
        Path target = Files.createTempFile(directory, "capture", ".png");
        Path writing = directory.resolve("capture.png.writing");
        Files.write(writing, PNG);
        ScreenshotFiles.publish(writing, target);
        assertArrayEquals(PNG, ScreenshotFiles.awaitPng(target,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1), 5, "capture timed out"));
        assertFalse(Files.exists(writing));
    }

    @Test
    void emptyAndAbsentCapturesRespectTheOriginalDeadline() throws Exception {
        Path empty = Files.createTempFile(directory, "empty", ".png");
        for (Path target : new Path[]{empty, directory.resolve("absent.png")}) {
            var failure = assertThrows(ProblemException.class, () -> ScreenshotFiles.awaitPng(
                    target, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20), 5, "capture timed out"));
            assertEquals(ProblemCode.SERVER_BUSY, failure.code());
            assertEquals("capture timed out", failure.getMessage());
        }
    }

    @Test
    void unrelatedIoErrorsAreNotTreatedAsPendingPublication() {
        assertThrows(IOException.class, () -> ScreenshotFiles.awaitPng(directory,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1), 5, "capture timed out"));
    }

    @Test
    void interruptedWaitDoesNotContinuePolling() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, () -> ScreenshotFiles.awaitPng(
                    directory.resolve("absent.png"), System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                    5, "capture timed out"));
        } finally {
            Thread.interrupted();
        }
    }
}
