package dev.example.mapi.fixture;

import dev.example.mapi.api.Mapi;
import dev.example.mapi.api.MapiApi;
import dev.example.mapi.api.MapiService;
import dev.example.mapi.api.PlatformType;
import dev.example.mapi.api.ServerStatusSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Consumer fixture: depends exclusively on the documented public Java API. */
public final class ApiConsumer implements MapiService {
    private final Path report;
    private final Set<String> checks = new LinkedHashSet<>();
    private final List<String> failures = new ArrayList<>();
    private Mapi mapi;
    private int starts;
    private int stops;
    private int snapshots;
    private boolean registered;

    /** Creates an isolated report writer. */
    public ApiConsumer(Path report) {
        this.report = report;
    }

    /** Starts sampling; the runner must supply an acceptance-only report path. */
    public static void start() {
        String output = System.getenv("MAPI_FIXTURE_REPORT");
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("MAPI_FIXTURE_REPORT is required by the acceptance fixture");
        }
        ApiConsumer consumer = new ApiConsumer(Path.of(output));
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mapi-public-api-fixture");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleWithFixedDelay(consumer::sample, 0, 100, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sampler.shutdownNow();
            consumer.write();
        }, "mapi-fixture-report"));
    }

    private synchronized void check(String member, boolean passed) {
        if (passed) checks.add(member);
        else failures.add(member);
    }

    /** Samples facade, registry, and snapshot behavior without loader internals. */
    public synchronized void sample() {
        try {
            if (!registered) {
                var optional = MapiApi.get();
                if (optional.isEmpty()) return;
                mapi = optional.orElseThrow();
                check("MapiApi.get", MapiApi.get().orElseThrow() == mapi);
                check("MapiApi.require", MapiApi.require() == mapi);
                check("Mapi.modVersion", !mapi.modVersion().isBlank());
                check("Mapi.apiVersion", !mapi.apiVersion().isBlank());
                check("Mapi.minecraftVersion", mapi.minecraftVersion().equals("26.2"));
                check("Mapi.platform", mapi.platform() == PlatformType.FABRIC || mapi.platform() == PlatformType.NEOFORGE);
                check("Mapi.platformVersion", !mapi.platformVersion().isBlank());
                check("PlatformType.id", Set.of("fabric", "neoforge").contains(mapi.platform().id()));
                check("Mapi.services", mapi.services() != null);
                check("MapiServices.register", mapi.services().register(id(), this) == this);
                check("MapiServices.get", mapi.services().get(id()).orElseThrow() == this
                        && mapi.services().get("fixture-missing").isEmpty());
                check("MapiServices.all", mapi.services().all().get(id()) == this);
                try {
                    mapi.services().all().clear();
                    check("registry-immutable", false);
                } catch (UnsupportedOperationException expected) {
                    check("registry-immutable", true);
                }
                check("MapiService.id", id().equals("acceptance-fixture"));
                registered = true;
            }
            var status = mapi.serverStatus();
            check("Mapi.serverStatus", status != null);
            status.ifPresent(this::snapshot);
            write();
        } catch (Exception failure) {
            failures.add(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            write();
        }
    }

    private void snapshot(ServerStatusSnapshot value) {
        snapshots++;
        check("ServerStatusSnapshot.capturedAtEpochMs", value.capturedAtEpochMs() > 0);
        check("ServerStatusSnapshot.startedAtEpochMs", value.startedAtEpochMs() > 0);
        check("ServerStatusSnapshot.playerCount", value.playerCount() >= 0);
        check("ServerStatusSnapshot.maxPlayers", value.maxPlayers() > 0);
        check("ServerStatusSnapshot.tickCount", value.tickCount() >= 0);
        check("ServerStatusSnapshot.averageTickTimeMs", Double.isFinite(value.averageTickTimeMs()) && value.averageTickTimeMs() >= 0);
        check("ServerStatusSnapshot.motd", value.motd() != null);
        check("ServerStatusSnapshot.uptimeMs", value.uptimeMs() == Math.max(0, value.capturedAtEpochMs() - value.startedAtEpochMs()));
    }

    /** Stable service registry identity. */
    @Override
    public String id() {
        return "acceptance-fixture";
    }

    /** Records observed server start callbacks. */
    @Override
    public synchronized void onServerStart() {
        starts++;
        check("MapiService.onServerStart", true);
        write();
    }

    /** Records observed server stop callbacks. */
    @Override
    public synchronized void onServerStop() {
        stops++;
        check("MapiService.onServerStop", stops <= starts);
        write();
    }

    /** Atomically writes a report that retains all first-attempt failures. */
    public synchronized void write() {
        try {
            Files.createDirectories(report.toAbsolutePath().getParent());
            String json = "{\"checks\":" + strings(checks) + ",\"failures\":" + strings(failures)
                    + ",\"starts\":" + starts + ",\"stops\":" + stops + ",\"snapshots\":" + snapshots + "}\n";
            Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
            Files.writeString(temporary, json);
            Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot write acceptance fixture report", failure);
        }
    }

    private static String strings(Iterable<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"");
        }
        return "[" + String.join(",", quoted) + "]";
    }
}
