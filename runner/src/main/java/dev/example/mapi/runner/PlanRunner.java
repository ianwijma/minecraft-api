package dev.example.mapi.runner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Plan execution over the public HTTP API (spec §1.1: fixture orchestration,
 * setup/teardown sequencing, machine-readable results). Plans are declarative
 * JSON: ordered steps, each an HTTP operation with an optional expectation.
 * Results are JSON reports; first-attempt outcomes stay visible — retries are
 * explicit, never relabeled (spec §18).
 */
final class PlanRunner {

    /** One executed step. */
    record StepResult(int index, String name, boolean ok, int status,
            Optional<String> errorCode, String detail) {

        /** @return the step as an ordered map for the report */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("name", name);
            map.put("ok", ok);
            map.put("status", status);
            errorCode.ifPresent(value -> map.put("errorCode", value));
            map.put("detail", detail);
            return map;
        }
    }

    /** Aggregate report of a plan run. */
    record Report(String planName, boolean ok, List<StepResult> steps, long startedAtEpochMs,
            long endedAtEpochMs) {

        /** @return the report as an ordered map */
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("plan", planName);
            map.put("ok", ok);
            map.put("startedAtEpochMs", startedAtEpochMs);
            map.put("endedAtEpochMs", endedAtEpochMs);
            map.put("durationMs", endedAtEpochMs - startedAtEpochMs);
            map.put("steps", steps.stream().map(StepResult::toMap).toList());
            return map;
        }
    }

    private final MapiClient client;

    PlanRunner(MapiClient client) {
        this.client = client;
    }

    /**
     * Executes a plan: {@code {"name":..., "steps":[{"name","method","path",
     * "body"?,"expect":{"status"?,"errorCode"?}}...]}}.
     *
     * @param planJson the plan document
     * @return the report (never {@code null}); a failed step stops the plan
     *     and the report reports {@code ok:false}
     */
    @SuppressWarnings("unchecked")
    Report run(String planJson) {
        Map<String, Object> plan = asMap(MiniJson.parse(planJson));
        String name = String.valueOf(plan.getOrDefault("name", "unnamed"));
        long started = System.currentTimeMillis();
        List<Object> steps = (List<Object>) plan.getOrDefault("steps", List.of());
        var results = new java.util.ArrayList<StepResult>();
        boolean ok = true;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = asMap(steps.get(i));
            StepResult result = runStep(i, step);
            results.add(result);
            if (!result.ok()) {
                ok = false;
                break;
            }
        }
        return new Report(name, ok, List.copyOf(results), started, System.currentTimeMillis());
    }

    private StepResult runStep(int index, Map<String, Object> step) {
        String name = String.valueOf(step.getOrDefault("name", "step-" + index));
        String method = String.valueOf(step.getOrDefault("method", "GET")).toUpperCase();
        String path = String.valueOf(step.get("path"));
        @SuppressWarnings("unchecked")
        Map<String, Object> expect = step.get("expect") == null
                ? Map.of()
                : asMap(step.get("expect"));
        try {
            MapiClient.Result response = switch (method) {
                case "GET" -> client.get(path);
                case "POST" -> client.post(path,
                        step.get("body") == null ? null : String.valueOf(step.get("body")));
                default -> new MapiClient.Result(0, Map.of(), "unsupported method: " + method, false);
            };
            int expectedStatus = expect.get("status") instanceof Number number
                    ? number.intValue() : (method.equals("GET") ? 200 : 200);
            Integer expectedErrorCode = null;
            if (expect.get("errorCode") instanceof String code) {
                expectedErrorCode = -1; // presence check
            }
            boolean statusOk = response.status() == expectedStatus;
            boolean codeOk = expectedErrorCode == null
                    || response.errorCode().isPresent();
            boolean ok = statusOk && codeOk;
            String detail = ok
                    ? "ok"
                    : ("expected status " + expectedStatus
                            + (expectedErrorCode != null ? " with an error envelope" : "")
                            + " but got " + response.status()
                            + response.errorCode().map(code -> " (" + code + ")").orElse(""));
            return new StepResult(index, name, ok, response.status(), response.errorCode(), detail);
        } catch (RuntimeException e) {
            return new StepResult(index, name, false, 0, Optional.empty(),
                    "transport failure: " + e.getMessage());
        }
    }

    /** Waits until the world session is ACTIVE (bounded, spec §6). */
    boolean waitWorldActive(long timeoutMs, int pollIntervalMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MapiClient.Result world = client.get("/api/v1/server/world");
            if (world.ok() && String.valueOf(world.body().get("phase")).equals("ACTIVE")) {
                return true;
            }
            Thread.sleep(pollIntervalMs);
        }
        return false;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return out;
        }
        throw new IllegalArgumentException("expected a JSON object, got: " + value);
    }
}
