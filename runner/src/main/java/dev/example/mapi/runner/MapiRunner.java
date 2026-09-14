package dev.example.mapi.runner;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The reference runner CLI (spec §1: one runner, versioned CLI,
 * machine-readable results). Subcommands print JSON reports and exit 0 on
 * success / 1 on failure / 2 on usage errors / 3 on the EULA gate.
 *
 * <p>EULA policy (spec §8.3): any command that provisions a Minecraft server
 * refuses to run unless the operator passes {@code --accept-eula}
 * explicitly. The runner never accepts the EULA silently.
 */
public final class MapiRunner {

    /** Runner CLI version. */
    public static final String VERSION = "0.1.0";

    private MapiRunner() {
    }

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.getenv()));
    }

    /**
     * Executable core (env injectable for tests).
     *
     * @param args command-line arguments
     * @param env  process environment
     * @return the process exit code
     */
    public static int run(String[] args, Map<String, String> env) {
        if (args.length == 0) {
            return usage();
        }
        String command = args[0];
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                String value = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                options.put(key, value);
            }
        }
        try {
            return switch (command) {
                case "version" -> {
                    System.out.println("{\"version\":\"" + VERSION + "\"}");
                    yield 0;
                }
                case "status" -> status(options, env);
                case "wait-world" -> waitWorld(options, env);
                case "run" -> runPlan(options, env);
                case "provision-server" -> provisionServer(options, env);
                case "replay" -> replay(options, env);
                default -> usage();
            };
        } catch (IllegalArgumentException e) {
            System.out.println("{\"error\":\"usage\",\"message\":\"" + jsonEscape(e.getMessage()) + "\"}");
            return 2;
        } catch (Exception e) {
            System.out.println("{\"error\":\"internal\",\"message\":\"" + jsonEscape(String.valueOf(e)) + "\"}");
            return 1;
        }
    }

    private static int usage() {
        System.out.println("{\"usage\":\"mapi-runner <version|status|wait-world|run|replay|provision-server>"
                + " [--base url] [--token ENV_NAME] ...\"}");
        return 2;
    }

    private static MapiClient client(Map<String, String> options, Map<String, String> env) {
        String base = options.getOrDefault("base", "http://127.0.0.1:25586");
        String tokenEnvName = options.getOrDefault("token", "MAPI_HTTP_TOKEN");
        String token = env.get(tokenEnvName);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("no bearer token; set --token <ENV_NAME> "
                    + "(the environment variable named there must hold the token)");
        }
        return new MapiClient(base, token);
    }

    private static int status(Map<String, String> options, Map<String, String> env) {
        MapiClient client = client(options, env);
        var health = client.get("/api/v1/health");
        var info = client.get("/api/v1/info");
        var world = client.get("/api/v1/server/world");
        boolean ok = health.ok() && info.ok() && world.ok();
        System.out.println(Map.of(
                "ok", ok,
                "health", health.status(),
                "info", info.status(),
                "world", world.status()));
        return ok ? 0 : 1;
    }

    private static int waitWorld(Map<String, String> options, Map<String, String> env)
            throws Exception {
        MapiClient client = client(options, env);
        long timeoutMs = Long.parseLong(options.getOrDefault("timeout", "60")) * 1000;
        long started = System.currentTimeMillis();
        boolean active = new PlanRunner(client).waitWorldActive(timeoutMs, 250);
        System.out.println(Map.of(
                "ok", active,
                "waitedMs", System.currentTimeMillis() - started));
        return active ? 0 : 1;
    }

    private static int runPlan(Map<String, String> options, Map<String, String> env)
            throws Exception {
        MapiClient client = client(options, env);
        String planFile = options.get("plan");
        if (planFile == null) {
            throw new IllegalArgumentException("--plan <file> is required");
        }
        String planJson = java.nio.file.Files.readString(java.nio.file.Path.of(planFile));
        PlanRunner.Report report = new PlanRunner(client).run(planJson);
        System.out.println(MiniJsonLiteral.reportJson(report));
        return report.ok() ? 0 : 1;
    }

    private static int replay(Map<String, String> options, Map<String, String> env)
            throws Exception {
        MapiClient client = client(options, env);
        String recordingFile = options.get("recording");
        if (recordingFile == null) {
            throw new IllegalArgumentException("--recording <file> is required");
        }
        Recorder.ReplayReport report = Recorder.replay(client,
                java.nio.file.Path.of(recordingFile));
        System.out.println(jsonOf(report));
        return report.ok() ? 0 : 1;
    }

    private static String jsonOf(Recorder.ReplayReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(report.ok());
        sb.append(",\"steps\":").append(report.steps().size());
        sb.append(",\"divergences\":[");
        for (int i = 0; i < report.divergences().size(); i++) {
            var divergence = report.divergences().get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(divergence.index())
                    .append(",\"name\":\"").append(jsonEscape(divergence.name())).append('"')
                    .append(",\"kind\":\"").append(divergence.kind()).append('"')
                    .append(",\"expected\":\"").append(jsonEscape(divergence.expected())).append('"')
                    .append(",\"actual\":\"").append(jsonEscape(divergence.actual())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static int provisionServer(Map<String, String> options, Map<String, String> env) {
        if (!"true".equals(options.get("accept-eula"))) {
            System.out.println(Map.of(
                    "error", "eula-required",
                    "message", "provisioning a Minecraft server requires explicit operator "
                            + "EULA acceptance: re-run with --accept-eula"));
            return 3;
        }
        // Provisioning lands with the supervisor chunks; the gate is real now.
        System.out.println(Map.of(
                "ok", false,
                "error", "not-implemented",
                "message", "server provisioning lands in a later runner chunk"));
        return 1;
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** JSON writer for the small report shapes the CLI prints. */
    private static final class MiniJsonLiteral {

        private MiniJsonLiteral() {
        }

        static String reportJson(PlanRunner.Report report) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"plan\":\"").append(jsonEscape(report.planName())).append('"');
            sb.append(",\"ok\":").append(report.ok());
            sb.append(",\"durationMs\":").append(report.endedAtEpochMs() - report.startedAtEpochMs());
            sb.append(",\"steps\":[");
            for (int i = 0; i < report.steps().size(); i++) {
                PlanRunner.StepResult step = report.steps().get(i);
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"index\":").append(step.index())
                        .append(",\"name\":\"").append(jsonEscape(step.name())).append('"')
                        .append(",\"ok\":").append(step.ok())
                        .append(",\"status\":").append(step.status());
                if (step.errorCode().isPresent()) {
                    sb.append(",\"errorCode\":\"").append(jsonEscape(step.errorCode().get())).append('"');
                }
                sb.append(",\"detail\":\"").append(jsonEscape(step.detail())).append("\"}");
            }
            sb.append("]}");
            return sb.toString();
        }
    }
}
