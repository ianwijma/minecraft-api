package dev.example.mapi.internal.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Path sandbox for the file surface (spec §4.5): every access is confined to
 * the instance game directory; symlinks are resolved and re-checked
 * (containment on the real path); a denylist protects API security files and
 * operator state (token, mapi.properties, eula.txt, server.properties,
 * ban/whitelist caches). Written files are created with no-follow on the
 * leaf so a symlink swap cannot redirect the write.
 */
public final class FileSandbox {

    /** Maximum accepted write size in bytes. */
    public static final int MAX_WRITE_BYTES = 1 << 20;

    /** Maximum returned file size in bytes (larger files report truncated). */
    public static final int MAX_READ_BYTES = 1 << 20;

    private static final Set<String> DENIED = Set.of(
            "mcapi/token",
            "config/mapi.properties",
            "eula.txt",
            "server.properties",
            "usercache.json",
            "ops.json",
            "whitelist.json",
            "banned-players.json",
            "banned-ips.json");

    private final Path root;
    private final Path realRoot;

    /**
     * @param gameDir instance game directory (the sandbox root)
     * @throws IOException when the root cannot be canonicalized
     */
    public FileSandbox(Path gameDir) throws IOException {
        this.root = gameDir.toAbsolutePath().normalize();
        this.realRoot = root.toRealPath();
    }

    /**
     * Deny decision for a relative path (exact or directory prefix).
     *
     * @param relative normalized relative path (never absolute)
     * @return true when the path is protected
     */
    public boolean isDenied(Path relative) {
        String normalized = relative.normalize().toString().replace('\\', '/');
        if (DENIED.contains(normalized)) {
            return true;
        }
        if (normalized.startsWith("mcapi/token.tmp-")) {
            return true;
        }
        return normalized.startsWith("logs/") || normalized.equals("logs");
    }

    /**
     * Resolves a user-supplied path to a real, contained path.
     *
     * @param userPath relative path; empty means the root
     * @return the contained real path, or empty when it escapes the sandbox
     *         (traversal or symlink escape)
     */
    public Optional<Path> resolve(String userPath) {
        String cleaned = userPath == null ? "" : userPath.trim().replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("\0")) {
            return Optional.empty();
        }
        Path relative = Path.of(cleaned.isEmpty() ? "." : cleaned).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            return Optional.empty();
        }
        if (isDenied(relative)) {
            return Optional.empty();
        }
        Path candidate = root.resolve(relative);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot)) {
                return Optional.empty();
            }
            return Optional.of(real);
        } catch (IOException e) {
            // Missing leaf: validate the deepest existing parent instead
            // (creation path), refusing escapes via not-yet-existing links.
            Path parent = candidate.getParent();
            while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                parent = parent.getParent();
            }
            if (parent == null) {
                return Optional.empty();
            }
            try {
                Path realParent = parent.toRealPath();
                if (!realParent.startsWith(realRoot)) {
                    return Optional.empty();
                }
                // Re-attach everything below the deepest existing parent so
                // intermediate directories are not lost.
                return Optional.of(realParent.resolve(root.relativize(candidate)));
            } catch (IOException e2) {
                return Optional.empty();
            }
        }
    }

    /**
     * Lists one directory.
     *
     * @param dir contained directory
     * @return entries (name, dir, size) sorted by name
     * @throws IOException on I/O failure
     */
    public List<Map<String, Object>> list(Path dir) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", entry.getFileName().toString());
                boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
                item.put("dir", directory);
                item.put("size", directory ? 0 : Files.size(entry));
                item.put("symlink", Files.isSymbolicLink(entry));
                entries.add(item);
            }
        }
        entries.sort(java.util.Comparator.comparing(item -> String.valueOf(item.get("name"))));
        return entries;
    }

    /**
     * Reads a file (bounded).
     *
     * @param file contained file
     * @return (encoding, content, truncated, size); encoding is utf-8 when
     *         the bytes are valid UTF-8, base64 otherwise
     * @throws IOException on I/O failure
     */
    public Map<String, Object> read(Path file) throws IOException {
        long size = Files.size(file);
        byte[] bytes = Files.readAllBytes(file);
        boolean truncated = bytes.length > MAX_READ_BYTES;
        byte[] content = truncated ? java.util.Arrays.copyOf(bytes, MAX_READ_BYTES) : bytes;
        String encoding;
        String payload;
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            payload = decoder.decode(java.nio.ByteBuffer.wrap(content)).toString();
            encoding = "utf-8";
        } catch (java.nio.charset.CharacterCodingException e) {
            encoding = "base64";
            payload = java.util.Base64.getEncoder().encodeToString(content);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("encoding", encoding);
        out.put("content", payload);
        out.put("truncated", truncated);
        out.put("size", size);
        return out;
    }

    /**
     * Writes a file (create or overwrite, no-follow on the leaf, bounded).
     *
     * @param file  contained target
     * @param bytes content
     * @throws IOException on I/O failure or when the target is a symlink or
     *                     the content exceeds the limit
     */
    public void write(Path file, byte[] bytes) throws IOException {
        if (bytes.length > MAX_WRITE_BYTES) {
            throw new IOException("content exceeds " + MAX_WRITE_BYTES + " bytes");
        }
        if (Files.isSymbolicLink(file)) {
            throw new IOException("refusing to write through a symlink");
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, bytes, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
    }
}
