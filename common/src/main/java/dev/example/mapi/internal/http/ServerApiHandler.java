package dev.example.mapi.internal.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.example.mapi.api.ServerStatusSnapshot;
import dev.example.mapi.internal.MapiRuntime;
import dev.example.mapi.internal.SnapshotResult;
import dev.example.mapi.internal.config.MapiConfig;
import dev.example.mapi.internal.json.JsonReader;
import dev.example.mapi.internal.json.JsonWriter;
import dev.example.mapi.internal.operation.ExecutionMode;
import dev.example.mapi.internal.operation.OperationDescriptor;
import dev.example.mapi.internal.operation.OperationGuard;
import dev.example.mapi.internal.operation.OperationRegistry;
import dev.example.mapi.internal.operation.Scope;
import dev.example.mapi.internal.operation.SideEffectClass;
import dev.example.mapi.internal.problem.ProblemCode;
import dev.example.mapi.internal.problem.ProblemException;
import dev.example.mapi.internal.problem.ProblemJson;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/** Package-private endpoint subsystem for server operations and observations. */
final class ServerApiHandler {

    private final HttpApiServer server;

    ServerApiHandler(HttpApiServer server) {
        this.server = server;
    }

    void handleFreeze(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.ticks.freeze", grants, body);
        var service = requireTickControl();
        var lease = service.holderFor(HttpApiRequest.stringField(body, "leaseId"));
        var state = server.runtime.callOnServerThread(() -> service.freeze(lease));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(tickStateMap(state)));
    }

    void handleUnfreeze(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.ticks.unfreeze", grants, body);
        var service = requireTickControl();
        var lease = service.holderFor(HttpApiRequest.stringField(body, "leaseId"));
        var state = server.runtime.callOnServerThread(() -> service.unfreeze(lease));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(tickStateMap(state)));
    }

    void handleTickLease(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.ticks.lease", grants, body);
        var service = requireTickControl();
        long ttlSeconds = HttpApiRequest.longField(body, "ttlSeconds", 300);
        if (ttlSeconds < 1 || ttlSeconds > 3600) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ttlSeconds must be between 1 and 3600");
        }
        String owner = "http:" + exchange.getRemoteAddress().getAddress();
        var lease = server.runtime.callOnServerThread(() -> service.acquireLease(owner, ttlSeconds * 1000));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leaseId", lease.id());
        result.put("topic", lease.topic());
        result.put("expiresAtEpochMs", lease.expiresAtEpochMs());
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(result));
    }

    void handleTickRate(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.ticks.rate", grants, body);
        var service = requireTickControl();
        Object rateValue = body.get("rate");
        if (!(rateValue instanceof Number rate) || !Float.isFinite(rate.floatValue())) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "rate must be a finite number");
        }
        var lease = service.holderFor(HttpApiRequest.stringField(body, "leaseId"));
        float applied = server.runtime.callOnServerThread(() -> service.setTickRate(lease, rate.floatValue()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appliedTickRate", applied);
        result.put("rateBounds", service.rateBounds());
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(result));
    }

    void handleTickStep(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants, boolean observe) throws IOException {
        server.checkAccess(observe ? "server.ticks.step-and-observe" : "server.ticks.step", grants, body);
        var service = requireTickControl();
        long ticks = HttpApiRequest.longField(body, "ticks", -1);
        if (ticks < 1 || ticks > 10_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 10000");
        }
        var lease = service.holderFor(HttpApiRequest.stringField(body, "leaseId"));
        String worldSessionId = server.runtime.worldLifecycle().currentSessionId().orElse(null);
        long deadline = System.currentTimeMillis() + 60_000;
        int stepTicks = (int) ticks;
        String label = observe ? HttpApiRequest.stringField(body, "label") : null;

        var job = server.runtime.jobs().submit(observe ? "ticks.step-and-observe" : "ticks.step",
                java.util.Optional.ofNullable(worldSessionId), deadline, context -> {
                    var operation = server.runtime.callOnServerThread(
                            () -> service.step(lease, stepTicks), 30_000);
                    var scheduled = operation.scheduled();
                    dev.example.mapi.internal.tick.TickControlBackend.StepResult result;
                    var observation = new java.util.concurrent.atomic.AtomicReference<dev.example.mapi.internal.snapshot.SnapshotCaptureService.Captured>();
                    boolean completed = false;
                    try {
                        result = dev.example.mapi.internal.tick.TickStepWaiter.await(scheduled, context,
                                () -> server.runtime.callOnServerThread(
                                        () -> service.stepState(lease, operation), 5_000));
                        var waited = result;
                        result = server.runtime.callOnServerThread(() -> {
                            service.holderFor(lease.id());
                            var capture = observe ? server.runtime.snapshotCapture() : java.util.Optional.<dev.example.mapi.internal.snapshot.SnapshotCaptureService>empty();
                            if (capture.isPresent()) {
                                observation.set(capture.get().captureFromServerThread(
                                        label == null ? "step-and-observe" : label, 10, 50));
                            }
                            service.completeStep(lease, operation);
                            return observation.get() == null ? waited
                                    : new dev.example.mapi.internal.tick.TickControlBackend.StepResult(
                                            waited.requested(), waited.completed(), observation.get().boundary());
                        }, 5_000);
                        completed = true;
                    } finally {
                        if (!completed) {
                            server.runtime.callOnServerThread(() -> {
                                service.cancelStep(lease, operation);
                                return null;
                            }, 5_000);
                        }
                    }
                    context.milestone("stepped", Map.of(
                            "requested", result.requested(),
                            "completed", result.completed(),
                            "boundary", result.boundaryTickCount()));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("requested", result.requested());
                    out.put("completed", result.completed());
                    out.put("boundary", result.boundaryTickCount());
                    if (observe) {
                        var captured = observation.get();
                        if (captured != null) {
                            out.put("snapshotId", captured.id());
                            out.put("snapshotBoundary", captured.boundary());
                        } else {
                            out.put("snapshotCaptured", false);
                        }
                    }
                    return out;
                });
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("jobId", job.id());
        accepted.put("state", "PENDING");
        accepted.put("worldSessionId", worldSessionId);
        HttpApiResponse.respond(exchange, 202, JsonWriter.write(accepted));
    }

    void handleTickSprint(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.ticks.sprint", grants, body);
        var service = requireTickControl();
        long ticks = HttpApiRequest.longField(body, "ticks", -1);
        if (ticks < 1 || ticks > 10_000) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "ticks must be between 1 and 10000");
        }
        var lease = service.holderFor(HttpApiRequest.stringField(body, "leaseId"));
        int sprintTicks = (int) ticks;
        dev.example.mapi.internal.tick.TickControlBackend.StepResult result =
                server.runtime.callOnServerThread(() -> service.sprint(lease, sprintTicks));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requested", result.requested());
        out.put("completed", result.completed());
        out.put("boundary", result.boundaryTickCount());
        out.put("note", "sprinting is asynchronous in vanilla; poll GET /server/ticks for completion");
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleTickStop(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.ticks.stop", grants, body);
        var service = requireTickControl();
        var lease = service.holderFor(HttpApiRequest.stringField(body, "leaseId"));
        boolean stopped = server.runtime.callOnServerThread(() -> {
            boolean a = service.stopStepping(lease);
            boolean b = service.stopSprinting(lease);
            return a || b;
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stopped", stopped);
        out.put("state", tickStateMap(service.state()));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(out));
    }

    void handleSnapshotCapture(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.snapshots.capture", grants, body);
        var capture = server.runtime.snapshotCapture()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "world queries are not available on this loader bridge"));
        var captured = capture.capture(HttpApiRequest.stringField(body, "label"),
                (int) HttpApiRequest.longField(body, "maxPlayers", 10),
                (int) HttpApiRequest.longField(body, "maxEntities", 50));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(captured.toMap()));
    }

    void handleSnapshotDiff(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.snapshots.diff", grants, body);
        String firstId = HttpApiRequest.stringField(body, "firstId");
        String secondId = HttpApiRequest.stringField(body, "secondId");
        if (firstId == null || secondId == null) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "firstId and secondId are required");
        }
        @SuppressWarnings("unchecked")
        java.util.List<String> includePaths = body.get("includePaths") instanceof java.util.List<?> list
                ? (java.util.List<String>) list
                : java.util.List.of();
        long maxChanges = HttpApiRequest.longField(body, "maxChanges", 1000);
        var result = server.runtime.snapshots().diff(firstId, secondId,
                new dev.example.mapi.internal.snapshot.DiffOptions(Set.copyOf(includePaths),
                        (int) maxChanges),
                System.currentTimeMillis());
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(result.toMap()));
    }

    void handleCommand(HttpExchange exchange, Map<String, Object> body,
            java.util.Set<Scope> grants) throws IOException {
        server.checkAccess("server.commands.dispatch", grants, body);
        var service = server.runtime.commands()
                .orElseThrow(() -> new ProblemException(ProblemCode.CAPABILITY_UNAVAILABLE,
                        "command dispatch is not available on this loader bridge"));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(service.dispatch(HttpApiRequest.stringField(body, "command"))));
    }

    void sendWorldInfo(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        var lifecycle = server.runtime.worldLifecycle();
        body.put("phase", lifecycle.phase().name());
        lifecycle.currentSessionId().ifPresent(value -> body.put("worldSessionId", value));
        var bridge = server.runtime.serverBridge();
        body.put("bridgeId", bridge.bridgeId());
        body.put("capabilities", bridge.supportedCapabilities().stream().sorted().toList());
        body.put("tickControl", server.runtime.tickControl().isPresent());
        body.put("worldQueries", server.runtime.worldQueries().isPresent());
        body.put("commands", server.runtime.commands().isPresent());
        body.put("clocks", server.runtime.clocks().toMap());
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(body));
    }

    void sendTickState(HttpExchange exchange) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocolVersion", HttpApiServer.PROTOCOL_VERSION);
        var service = server.runtime.tickControl();
        if (service.isEmpty()) {
            body.put("available", false);
            HttpApiResponse.respond(exchange, 200, JsonWriter.write(body));
            return;
        }
        var state = server.runtime.callOnServerThread(service.get()::state);
        body.put("available", true);
        body.putAll(tickStateMap(state));
        body.put("rateBounds", service.get().rateBounds());
        service.get().holderId().ifPresent(value -> body.put("leaseId", value));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(body));
    }

    Map<String, Object> tickStateMap(dev.example.mapi.internal.tick.TickControlBackend.State state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("frozen", state.frozen());
        map.put("sprinting", state.sprinting());
        map.put("tickRate", state.tickRate());
        map.put("tickCount", state.tickCount());
        return map;
    }

    void sendPlayerQuery(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        int max = HttpApiRequest.intParam(exchange, "max", 20);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(Map.of("players", service.players(max))));
    }

    void sendEntityQuery(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        var query = exchange.getRequestURI().getRawQuery();
        Map<String, String> params = HttpApiRequest.queryParams(query);
        String dimension = params.getOrDefault("dimension", "minecraft:overworld");
        double x = Double.parseDouble(params.getOrDefault("x", "0"));
        double y = Double.parseDouble(params.getOrDefault("y", "0"));
        double z = Double.parseDouble(params.getOrDefault("z", "0"));
        int radius = HttpApiRequest.intParam(exchange, "radius", 32);
        int max = HttpApiRequest.intParam(exchange, "max", 50);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(Map.of("entities",
                service.entities(dimension, x, y, z, radius, max))));
    }

    void sendBlockQuery(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        Map<String, String> params = HttpApiRequest.queryParams(exchange.getRequestURI().getRawQuery());
        String dimension = params.getOrDefault("dimension", "minecraft:overworld");
        try {
            int x = Integer.parseInt(params.getOrDefault("x", "0"));
            int y = Integer.parseInt(params.getOrDefault("y", "0"));
            int z = Integer.parseInt(params.getOrDefault("z", "0"));
            HttpApiResponse.respond(exchange, 200, JsonWriter.write(service.block(dimension, x, y, z)
                    .map(Map.class::cast)
                    .orElseGet(() -> Map.of("blockId", "minecraft:air", "unloaded", false))));
        } catch (NumberFormatException e) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "x/y/z must be integers");
        }
    }

    void sendRegistryList(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(Map.of("registries", service.registries())));
    }

    void sendRegistryEntries(HttpExchange exchange) throws IOException {
        var service = requireWorldQueries();
        Map<String, String> params = HttpApiRequest.queryParams(exchange.getRequestURI().getRawQuery());
        String registryId = params.get("registryId");
        if (registryId == null || registryId.isBlank()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST, "registryId is required");
        }
        int max = HttpApiRequest.intParam(exchange, "max", 100);
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(Map.of("registryId", registryId,
                "entries", service.registryEntries(registryId, max))));
    }

    void sendJobView(HttpExchange exchange, String jobId) throws IOException {
        var view = server.runtime.jobs().view(jobId)
                .orElseThrow(() -> new ProblemException(ProblemCode.NOT_FOUND, "unknown job: " + jobId));
        HttpApiResponse.respond(exchange, 200, JsonWriter.write(view.toMap()));
    }

    private dev.example.mapi.internal.tick.TickControlService requireTickControl() {
        return server.runtime.tickControl().orElseThrow(() -> new ProblemException(
                ProblemCode.CAPABILITY_UNAVAILABLE,
                "tick control is not available on this loader bridge"));
    }

    private dev.example.mapi.internal.query.WorldQueryService requireWorldQueries() {
        return server.runtime.worldQueries().orElseThrow(() -> new ProblemException(
                ProblemCode.CAPABILITY_UNAVAILABLE,
                "world queries are not available on this loader bridge"));
    }
}
