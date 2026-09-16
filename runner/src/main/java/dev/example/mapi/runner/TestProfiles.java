package dev.example.mapi.runner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned test profiles and the preflight validator (spec §8). Profiles are
 * opt-in JSON documents for dedicated test directories only; installing the
 * mod never touches a user's normal settings. The preflight produces the
 * §8.4 output: requested vs effective settings, deviations, unsupported
 * settings, identity conflicts, readiness, and fatal vs advisory issues.
 */
final class TestProfiles {

    /** Client profile schema (§8.1) with allowed ranges. */
    static final Set<String> CLIENT_KEYS = Set.of(
            "profileId", "version", "windowWidth", "windowHeight", "fullscreen",
            "guiScale", "language", "mouseSensitivity", "autoJump",
            "pauseOnLostFocus", "renderDistance", "simulationDistance",
            "frameRateLimit", "vsync", "narrator", "resourcePacks");

    /** Server profile schema (§8.2). */
    static final Set<String> SERVER_KEYS = Set.of(
            "profileId", "version", "onlineMode", "port", "maxPlayers",
            "seed", "viewDistance", "simulationDistance", "difficulty",
            "gamemode", "allowlist", "lanPublished");

    private TestProfiles() {
    }

    /**
     * Validates a client or server profile document.
     *
     * @param profileJson the profile document
     * @param kind        {@code "client"} or {@code "server"}
     * @return the §8.4 preflight report as an ordered map
     */
    static Map<String, Object> preflight(String profileJson, String kind) {
        Set<String> schema = "server".equals(kind) ? SERVER_KEYS : CLIENT_KEYS;
        Map<String, Object> requested;
        try {
            requested = MiniJsonWriter.asObject(MiniJson.parse(profileJson));
        } catch (IllegalArgumentException e) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("ok", false);
            report.put("fatal", List.of(Map.of("issue", "profile is not valid JSON: " + e.getMessage())));
            return report;
        }

        List<Map<String, Object>> fatal = new ArrayList<>();
        List<Map<String, Object>> advisory = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();

        if (requested.getOrDefault("profileId", "").toString().isBlank()) {
            fatal.add(Map.of("issue", "profileId is required"));
        }
        if (requested.getOrDefault("version", "").toString().isBlank()) {
            fatal.add(Map.of("issue", "profile version is required"));
        }
        for (String key : requested.keySet()) {
            if (!schema.contains(key)) {
                unsupported.add(key);
                advisory.add(Map.of("issue", "unknown setting ignored: " + key));
            }
        }

        // Range validation (mirrors the mod-side bounds where they exist).
        if (requested.get("windowWidth") instanceof Number width
                && (width.intValue() < 320 || width.intValue() > 3840)) {
            fatal.add(Map.of("issue", "windowWidth must be 320..3840"));
        }
        if (requested.get("windowHeight") instanceof Number height
                && (height.intValue() < 240 || height.intValue() > 2160)) {
            fatal.add(Map.of("issue", "windowHeight must be 240..2160"));
        }
        if (requested.get("guiScale") instanceof Number scale
                && (scale.intValue() < 0 || scale.intValue() > 4)) {
            fatal.add(Map.of("issue", "guiScale must be 0..4"));
        }
        if (requested.get("port") instanceof Number port
                && (port.intValue() < 1 || port.intValue() > 65535)) {
            fatal.add(Map.of("issue", "port must be 1..65535"));
        }
        if (requested.get("onlineMode") instanceof Boolean online && online) {
            advisory.add(Map.of("issue",
                    "online-mode profiles require externally provisioned accounts (spec §7.1); "
                            + "the mod never stores credentials"));
        }
        if (requested.get("lanPublished") instanceof Boolean lan && lan) {
            advisory.add(Map.of("issue",
                    "publishing an integrated server exposes the game port; requires "
                            + "server.lan.enabled=true in the instance config (spec §9.3)"));
        }

        // Effective settings equal requested here: the live game reconciles
        // platform adjustments, which the preflight cannot observe; deviations
        // are reported by the runtime surface (window revision, effective
        // framebuffer size), not by this static pass.
        boolean ok = fatal.isEmpty();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", ok);
        report.put("kind", kind);
        report.put("profileId", requested.getOrDefault("profileId", ""));
        report.put("version", requested.getOrDefault("version", ""));
        report.put("requested", requested);
        report.put("effective", requested);
        report.put("deviations", List.of());
        report.put("unsupportedSettings", unsupported);
        report.put("identityConflicts", List.of());
        report.put("renderingReady", ok);
        report.put("missingPermissions", List.of());
        report.put("fatal", fatal);
        report.put("advisory", advisory);
        return report;
    }
}
