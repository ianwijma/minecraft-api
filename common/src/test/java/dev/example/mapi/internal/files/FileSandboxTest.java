package dev.example.mapi.internal.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSandboxTest {

    @TempDir
    Path gameDir;

    @Test
    void resolvesInsideRootAndRejectsTraversal() throws Exception {
        FileSandbox sandbox = new FileSandbox(gameDir);
        assertTrue(sandbox.resolve("").isPresent());
        assertTrue(sandbox.resolve("world/level.dat").isPresent());
        assertTrue(sandbox.resolve("a/../b.txt").isPresent());
        assertTrue(sandbox.resolve("../outside").isEmpty());
        assertTrue(sandbox.resolve("/absolute").isEmpty());
        assertTrue(sandbox.resolve("a/../../b").isEmpty());
        assertTrue(sandbox.resolve("with\0nul").isEmpty());
    }

    @Test
    void denylistBlocksSecurityFiles() throws Exception {
        FileSandbox sandbox = new FileSandbox(gameDir);
        assertTrue(sandbox.isDenied(Path.of("mcapi/token")));
        assertTrue(sandbox.resolve("mcapi/token").isEmpty());
        assertTrue(sandbox.resolve("mcapi/token.tmp-123").isEmpty());
        assertTrue(sandbox.resolve("config/mapi.properties").isEmpty());
        assertTrue(sandbox.resolve("eula.txt").isEmpty());
        assertTrue(sandbox.resolve("server.properties").isEmpty());
        assertTrue(sandbox.resolve("ops.json").isEmpty());
        assertTrue(sandbox.resolve("logs/x.log").isEmpty(), "logs are protected too");
        assertFalse(sandbox.isDenied(Path.of("world/level.dat")));
        assertFalse(sandbox.resolve("world/level.dat").isEmpty());
    }

    @Test
    void symlinksPointingOutsideAreRejected() throws Exception {
        Path outside = Files.createTempDirectory("mapi-sandbox-escape");
        Path link = gameDir.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // filesystem without symlink support
        }
        FileSandbox sandbox = new FileSandbox(gameDir);
        assertTrue(sandbox.resolve("escape").isEmpty(),
                "symlinks out of the root must resolve to empty");

    }

    @Test
    void readReportsUtf8AndBase64Encodings() throws Exception {
        FileSandbox sandbox = new FileSandbox(gameDir);
        Path text = gameDir.resolve("note.txt");
        Files.writeString(text, "hello", StandardCharsets.UTF_8);
        var readText = sandbox.read(text);
        assertEquals("utf-8", readText.get("encoding"));
        assertEquals("hello", readText.get("content"));
        assertEquals(5L, readText.get("size"));

        Path binary = gameDir.resolve("blob.bin");
        byte[] bytes = {(byte) 0xff, 0x00, (byte) 0xfe};
        Files.write(binary, bytes);
        var readBinary = sandbox.read(binary);
        assertEquals("base64", readBinary.get("encoding"));
        assertTrue(java.util.Arrays.equals(bytes, java.util.Base64.getDecoder()
                .decode((String) readBinary.get("content"))));
    }

    @Test
    void writeRoundsTripAndRefusesSymlinkLeaf() throws Exception {
        FileSandbox sandbox = new FileSandbox(gameDir);
        Path target = gameDir.resolve("dir").resolve("file.txt");
        sandbox.write(target, "written".getBytes(StandardCharsets.UTF_8));
        assertEquals("written", Files.readString(target, StandardCharsets.UTF_8));

        try {
            Files.delete(target);
            Files.createSymbolicLink(target, gameDir.resolve("somewhere-else.txt"));
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> sandbox.write(target, "no".getBytes(StandardCharsets.UTF_8)),
                    "writing through a symlink must be refused");
        } catch (UnsupportedOperationException | java.io.IOException e) {
            // filesystem without symlink support
        }
    }
}
