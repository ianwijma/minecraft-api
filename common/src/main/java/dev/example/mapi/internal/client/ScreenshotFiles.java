package dev.example.mapi.internal.client;

import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Publication and bounded off-thread reads for asynchronously encoded screenshots. */
public final class ScreenshotFiles {

    private ScreenshotFiles() {
    }

    /** Publishes a fully encoded PNG, atomically when the filesystem supports it. */
    public static void publish(Path writing, Path target) throws IOException {
        try {
            Files.move(writing, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(writing, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Waits for one pending capture without scheduling another GPU readback.
     * Empty placeholders and paths briefly absent during publication remain
     * pending; other I/O errors are propagated. The deadline uses nanoTime.
     */
    public static byte[] awaitPng(Path target, long deadlineNanos, long pollMillis,
            String deadlineMessage) throws IOException, InterruptedException {
        while (true) {
            try {
                byte[] bytes = Files.readAllBytes(target);
                if (bytes.length > 0) {
                    return bytes;
                }
            } catch (NoSuchFileException ignored) {
                // Non-atomic replacement can briefly remove the empty placeholder.
            }
            if (System.nanoTime() >= deadlineNanos) {
                throw new ProblemException(ProblemCode.SERVER_BUSY, deadlineMessage);
            }
            Thread.sleep(pollMillis);
        }
    }
}
