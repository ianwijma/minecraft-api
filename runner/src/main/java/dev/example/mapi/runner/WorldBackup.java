package dev.example.mapi.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Safe-boundary world backup/restore (spec §17.2). The runner owns file
 * copy/restore; the mod reports lifecycle state. Full-world backups happen
 * only while the affected server is stopped (restore: likewise) — the mod
 * performs graceful shutdown on request, and the runner waits for the world
 * session to leave ACTIVE before touching any file. No live backup: it does
 * not quiesce mod-owned writers (spec §17.2 Extension).
 */
final class WorldBackup {

    /** Backup outcome. */
    record Result(boolean ok, String detail, Map<String, Object> manifest) {

        /** @return the outcome as an ordered map */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ok", ok);
            map.put("detail", detail);
            if (manifest != null) {
                map.putAll(manifest);
            }
            return map;
        }
    }

    private final MapiClient client;

    WorldBackup(MapiClient client) {
        this.client = client;
    }

    /**
     * Backs up {@code worldDir} into {@code backupDir} after driving the
     * server to a stopped state.
     *
     * @param worldDir    the instance's world directory (e.g.
     *                    {@code <run>/world})
     * @param backupDir   target directory (created)
     * @param shutdownS   seconds to wait for the process to stop after
     *                    requesting shutdown
     * @return the outcome with a manifest
     * @throws IOException on filesystem failure
     * @throws InterruptedException when waiting is interrupted
     */
    Result backup(Path worldDir, Path backupDir, int shutdownS)
            throws IOException, InterruptedException {
        if (!Files.isDirectory(worldDir)) {
            return new Result(false, "world directory does not exist: " + worldDir, null);
        }
        var world = client.get("/api/v1/server/world");
        String phase = String.valueOf(world.body().getOrDefault("phase", "NONE"));
        if ("ACTIVE".equals(phase) || "LOADING".equals(phase)) {
            var shutdown = client.post("/api/v1/process/shutdown", null);
            if (!shutdown.ok()) {
                return new Result(false, "graceful shutdown refused by the instance: "
                        + shutdown.status(), null);
            }
        }
        // Bounded wait for the safe boundary (spec §17.2: process stopped,
        // files closed).
        long deadline = System.currentTimeMillis() + shutdownS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            var check = client.get("/api/v1/server/world");
            if (!check.ok()) {
                break; // listener gone: process is stopping/stopped
            }
            String now = String.valueOf(check.body().getOrDefault("phase", "NONE"));
            if ("NONE".equals(now)) {
                break;
            }
            Thread.sleep(250);
        }
        var confirm = client.get("/api/v1/server/world");
        if (confirm.ok() && !"NONE".equals(String.valueOf(
                confirm.body().getOrDefault("phase", "NONE")))) {
            return new Result(false, "server did not reach a stopped state within the bound",
                    null);
        }

        Files.createDirectories(backupDir);
        long files = 0;
        long bytes = 0;
        try (Stream<Path> stream = Files.walk(worldDir)) {
            for (Path source : stream.sorted().toList()) {
                Path target = backupDir.resolve(worldDir.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    files++;
                    bytes += Files.size(source);
                }
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("worldDir", worldDir.toString());
        manifest.put("backupDir", backupDir.toString());
        manifest.put("files", files);
        manifest.put("bytes", bytes);
        manifest.put("backupAtEpochMs", System.currentTimeMillis());
        manifest.put("consistencyScope", "full-world at a stopped boundary; "
                + "mod-owned external files are NOT quiesced (spec §17.2)");
        Files.writeString(backupDir.resolve("backup-manifest.json"),
                MiniJsonWriter.write(manifest));
        return new Result(true, "backed up " + files + " files", manifest);
    }

    /**
     * Restores {@code backupDir} over {@code worldDir}. Refuses while a
     * server is running (restore occurs only while stopped, spec §17.2).
     *
     * @param backupDir source of the restore
     * @param worldDir  target world directory
     * @return the outcome
     * @throws IOException on filesystem failure
     */
    Result restore(Path backupDir, Path worldDir) throws IOException {
        if (!Files.isDirectory(backupDir)) {
            return new Result(false, "backup directory does not exist: " + backupDir, null);
        }
        var world = client.get("/api/v1/server/world");
        if (world.ok() && !"NONE".equals(String.valueOf(
                world.body().getOrDefault("phase", "NONE")))) {
            return new Result(false, "restore refused: the server is still running "
                    + "(stop it first)", null);
        }
        try (Stream<Path> existing = Files.walk(worldDir)) {
            existing.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            return new Result(false, "delete failed: " + e.getCause(), null);
        }
        Files.createDirectories(worldDir);
        long files = 0;
        try (Stream<Path> stream = Files.walk(backupDir)) {
            for (Path source : stream.sorted().toList()) {
                if ("backup-manifest.json".equals(source.getFileName().toString())) {
                    continue;
                }
                Path target = worldDir.resolve(backupDir.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
            }
        }
        return new Result(true, "restored " + files + " files", null);
    }
}
