package dev.example.mapi.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reproducibility bundle assembly (spec §1.1): copies the recording, plan
 * report, profiles, and a manifest with the instance's published versions
 * into one directory. The mod publishes local facts; the runner assembles
 * them (responsibility split, docs/runner-boundary.md).
 */
final class BundleWriter {

    private final MapiClient client;

    BundleWriter(MapiClient client) {
        this.client = client;
    }

    /**
     * Assembles the bundle.
     *
     * @param out           target directory (created if absent)
     * @param recordingFile JSONL recording, may be {@code null}
     * @param planReport    plan report JSON, may be {@code null}
     * @param profileFile   profile document, may be {@code null}
     * @return the manifest as an ordered map
     * @throws IOException on filesystem failure
     */
    Map<String, Object> write(Path out, Path recordingFile, String planReport,
            Path profileFile) throws IOException {
        Files.createDirectories(out);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("createdAtEpochMs", System.currentTimeMillis());

        var info = client.get("/api/v1/info");
        if (info.ok()) {
            Files.writeString(out.resolve("info.json"), info.rawBody());
            manifest.put("instance", info.body());
        } else {
            manifest.put("instanceError", "info unavailable: " + info.status());
        }
        var world = client.get("/api/v1/server/world");
        if (world.ok()) {
            Files.writeString(out.resolve("world.json"), world.rawBody());
            manifest.put("world", world.body());
        }
        var logs = client.get("/api/v1/logs?limit=1000");
        if (logs.ok()) {
            Files.writeString(out.resolve("logs.json"), logs.rawBody());
            manifest.put("logsCaptured", true);
        }

        if (recordingFile != null && Files.isRegularFile(recordingFile)) {
            Path target = out.resolve("recording.jsonl");
            Files.copy(recordingFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            manifest.put("recording", target.getFileName().toString());
        }
        if (planReport != null) {
            Files.writeString(out.resolve("plan-report.json"), planReport);
            manifest.put("planReport", "plan-report.json");
        }
        if (profileFile != null && Files.isRegularFile(profileFile)) {
            Path target = out.resolve("profile.json");
            Files.copy(profileFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            manifest.put("profile", target.getFileName().toString());
            Map<String, Object> preflight = TestProfiles.preflight(
                    Files.readString(profileFile),
                    Files.readString(profileFile).contains("\"onlineMode\"") ? "server" : "client");
            Files.writeString(out.resolve("preflight.json"), MiniJsonWriter.write(preflight));
            manifest.put("preflight", "preflight.json");
        }

        Files.writeString(out.resolve("manifest.json"), MiniJsonWriter.write(manifest));
        return manifest;
    }
}
