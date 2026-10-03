package dev.example.mapi.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/** Identifies the artifact supplying runtime classes without exposing its filesystem path. */
public record RuntimeArtifactIdentity(String kind, String sha256) {

    /** @return the cached identity of the artifact that loaded {@link MapiRuntime} */
    public static RuntimeArtifactIdentity current() {
        return Current.VALUE;
    }

    /** @return artifact kind and, for a regular JAR, its SHA-256 digest */
    public Map<String, Object> toMap() {
        return sha256 == null ? Map.of("kind", kind) : Map.of("kind", kind, "sha256", sha256);
    }

    static RuntimeArtifactIdentity inspect(Path source) {
        if (source == null) {
            return new RuntimeArtifactIdentity("unavailable", null);
        }
        if (Files.isDirectory(source)) {
            return new RuntimeArtifactIdentity("directory", null);
        }
        if (!Files.isRegularFile(source) || !source.getFileName().toString().endsWith(".jar")) {
            return new RuntimeArtifactIdentity("unavailable", null);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = new DigestInputStream(Files.newInputStream(source), digest)) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            return new RuntimeArtifactIdentity("jar", HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | java.security.NoSuchAlgorithmException | SecurityException e) {
            return new RuntimeArtifactIdentity("unavailable", null);
        }
    }

    private static RuntimeArtifactIdentity discover() {
        try {
            var source = MapiRuntime.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return new RuntimeArtifactIdentity("unavailable", null);
            }
            return inspect(Path.of(source.getLocation().toURI()));
        } catch (Exception e) {
            return new RuntimeArtifactIdentity("unavailable", null);
        }
    }

    private static final class Current {
        private static final RuntimeArtifactIdentity VALUE = discover();
    }
}
