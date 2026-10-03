package dev.example.mapi.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeArtifactIdentityTest {

    @TempDir
    Path directory;

    @Test
    void jarIdentityMatchesActualBytesWithoutDisclosingPath() throws Exception {
        Path jar = directory.resolve("mapi.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("marker"));
            output.write(new byte[] {1, 2, 3});
            output.closeEntry();
        }
        var identity = RuntimeArtifactIdentity.inspect(jar);
        assertEquals("jar", identity.kind());
        assertEquals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar))), identity.sha256());
        assertEquals(java.util.Set.of("kind", "sha256"), identity.toMap().keySet());
        assertFalse(identity.toMap().toString().contains(jar.toString()));
    }

    @Test
    void developmentDirectoryCannotPassAsPackagedArtifact() {
        var identity = RuntimeArtifactIdentity.inspect(directory);
        assertEquals("directory", identity.kind());
        assertFalse(identity.toMap().containsKey("sha256"));
    }

    @Test
    void unavailableSourcesDoNotClaimJarIdentity() {
        assertEquals("unavailable", RuntimeArtifactIdentity.inspect(null).kind());
        assertEquals("unavailable", RuntimeArtifactIdentity.inspect(directory.resolve("absent.jar")).kind());
        assertTrue(RuntimeArtifactIdentity.current().toMap().containsKey("kind"));
    }
}
