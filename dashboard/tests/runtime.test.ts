import { test } from "node:test";
import assert from "node:assert/strict";
import {
  createApiClient,
  createConnectionRuntime,
  MapiApiError,
} from "../src/lib/runtime.ts";
import {
  classifyCapabilities,
  operationAvailability,
} from "../src/lib/capabilities.ts";
import type { ConnectionProfile, RuntimeEvent } from "../src/lib/types.ts";
import { operations } from "../src/generated/operations.ts";

const profile: ConnectionProfile = {
  id: "test",
  name: "Test",
  baseUrl: "http://127.0.0.1:25586",
  token: "test-token",
  rememberToken: false,
};
function json(
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });
}

test("native fetch transport sends bearer auth without forbidden Host and exposes metadata", async () => {
  const old = globalThis.fetch;
  let init: RequestInit | undefined;
  globalThis.fetch = async (_input, options) => {
    init = options;
    return json({ protocolVersion: 1 });
  };
  try {
    const result = await createApiClient(profile).request(
      "GET",
      "/api/v1/health",
    );
    assert.equal(result.status, 200);
    assert.equal(result.data.protocolVersion, 1);
    assert.equal(
      new Headers(init?.headers).get("authorization"),
      "Bearer test-token",
    );
    assert.equal(new Headers(init?.headers).has("host"), false);
    assert.ok(result.durationMs >= 0);
  } finally {
    globalThis.fetch = old;
  }
});

test("equivalent reads coalesce and cancellation of one reader does not cancel the other", async () => {
  const old = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    await new Promise((r) => setTimeout(r, 25));
    return json({ ok: true });
  };
  try {
    const client = createApiClient(profile),
      controller = new AbortController();
    const a = client.request("GET", "/api/v1/health", {
      signal: controller.signal,
    });
    const b = client.request("GET", "/api/v1/health");
    controller.abort();
    await assert.rejects(a);
    assert.deepEqual((await b).data, { ok: true });
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = old;
  }
});

test("mutations are sent once and return structured errors without retry", async () => {
  const old = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => {
    calls++;
    return json(
      {
        protocolVersion: 1,
        error: {
          code: "LEASE_HELD",
          message: "busy",
          details: { topic: "input" },
        },
      },
      409,
      { "Retry-After": "2" },
    );
  };
  try {
    await assert.rejects(
      createApiClient(profile).request("POST", "/api/v1/client/control/lease", {
        body: { ttlSeconds: 60 },
      }),
      (e: unknown) =>
        e instanceof MapiApiError &&
        e.code === "LEASE_HELD" &&
        e.status === 409,
    );
    assert.equal(calls, 1);
  } finally {
    globalThis.fetch = old;
  }
});

test("explicit input lease actions renew through HTTP and record failures without retry", async () => {
  const old = globalThis.fetch;
  const requests: Array<{ method?: string; body: any }> = [];
  globalThis.fetch = async (_input, init) => {
    requests.push({
      method: init?.method,
      body: typeof init?.body === "string" ? JSON.parse(init.body) : undefined,
    });
    if (requests.length === 1)
      return json(
        { leaseId: "input-lease-1", expiresAtEpochMs: Date.now() + 60_000 },
        200,
        { "x-mapi-protocol-version": "1" },
      );
    return json(
      { error: { code: "LEASE_REQUIRED", message: "renewal rejected" } },
      409,
      { "x-mapi-protocol-version": "1" },
    );
  };
  const runtime = createConnectionRuntime({
    ...profile,
    id: "explicit-input-lease",
    token: "explicit-input-lease-token",
  });
  try {
    const operation = operations.find(
      (item) => item.id === "acquireClientControlLease",
    );
    assert.ok(operation);
    const first = await runtime.execute(operation, { ttlSeconds: 60 });
    assert.equal(first.status, 200);
    assert.equal(first.responseHeaders?.["x-mapi-protocol-version"], "1");
    assert.deepEqual(requests[0], {
      method: "POST",
      body: { ttlSeconds: 60 },
    });

    const second = await runtime.execute(operation, { ttlSeconds: 120 });
    assert.equal(second.status, 409);
    assert.equal(second.error?.code, "LEASE_REQUIRED");
    assert.deepEqual(second.request.body, {
      leaseId: "[REDACTED]",
      ttlSeconds: 120,
    });
    assert.equal(requests.length, 2);
    assert.deepEqual(requests[1], {
      method: "POST",
      body: { leaseId: "input-lease-1", ttlSeconds: 120 },
    });
  } finally {
    runtime.dispose();
    globalThis.fetch = old;
  }
});

test("job polling failure preserves accepted 202 admission without retrying POST", async () => {
  const old = globalThis.fetch;
  let postCalls = 0;
  let pollCalls = 0;
  globalThis.fetch = async (input, init) => {
    const url = new URL(String(input));
    if (
      url.pathname === "/api/v1/process/shutdown" &&
      init?.method === "POST"
    ) {
      postCalls++;
      return json({ jobId: "accepted-job", state: "PENDING" }, 202);
    }
    if (url.pathname === "/api/v1/jobs/accepted-job") {
      pollCalls++;
      return json(
        { error: { code: "JOB_STATUS_UNAVAILABLE", message: "poll failed" } },
        503,
      );
    }
    return json(
      { error: { code: "NOT_FOUND", message: "unexpected request" } },
      404,
    );
  };
  const runtime = createConnectionRuntime({
    ...profile,
    id: "job-poll-error",
    token: "job-poll-error-token",
  });
  try {
    const operation = operations.find((item) => item.id === "requestShutdown");
    assert.ok(operation);
    const record = await runtime.execute(operation, {});
    assert.equal(record.status, 202);
    assert.equal((record.response as any)?.admission?.jobId, "accepted-job");
    assert.equal((record.response as any)?.completion?.state, "UNVERIFIED");
    assert.equal(record.error?.code, "JOB_STATUS_UNAVAILABLE");
    assert.equal(record.error?.status, 503);
    assert.equal(postCalls, 1);
    assert.equal(pollCalls, 1);
  } finally {
    runtime.dispose();
    globalThis.fetch = old;
  }
});

test("runtime does not dispatch a destructive action without fresh confirmation", async () => {
  const op: any = {
    id: "deleteWorld",
    method: "POST",
    path: "/api/v1/client/worlds/delete",
    parameters: [],
    security: {
      requiredScopes: [],
      destructive: true,
      sideEffectClass: "game",
      requiresLease: false,
      supportedExecutionModes: [],
    },
  };
  const runtime = createConnectionRuntime(profile);
  await assert.rejects(
    runtime.execute(op, { worldId: "x" }),
    /fresh confirmation/,
  );
  runtime.dispose();
});

test("dedicated server client bridge sentinel is unavailable and classifies as dedicated", () => {
  const capability = classifyCapabilities({
    serverStatus: { running: true },
    worldInfo: {
      phase: "ACTIVE",
      capabilities: [],
      tickControl: false,
      worldQueries: true,
      commands: true,
    },
    clientInfo: {
      bridgeId: "none",
      capabilities: [],
      input: false,
      screenshots: false,
      window: false,
    },
  });
  assert.equal(capability.mode, "dedicated");
  assert.equal(capability.client, "unavailable");
  assert.equal(capability.world, "available");
});

test("connection probe accepts the dedicated server 200 response with bridgeId none", async () => {
  const old = globalThis.fetch;
  let worldPhase = "ACTIVE";
  let worldSessionId: string | undefined = "world-one";
  globalThis.fetch = async (input) => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith("/health"))
      return json({ protocolVersion: 1, status: "ok" });
    if (path.endsWith("/info"))
      return json({ protocolVersion: 1, name: "mapi" });
    if (path.endsWith("/operations")) return json({ operations: [] });
    if (path.endsWith("/server/status")) return json({ running: true });
    if (path.endsWith("/server/world"))
      return json({
        phase: worldPhase,
        ...(worldSessionId ? { worldSessionId } : {}),
        capabilities: [],
        tickControl: false,
        worldQueries: true,
        commands: true,
      });
    if (path.endsWith("/client"))
      return json({
        protocolVersion: 1,
        bridgeId: "none",
        capabilities: [],
        input: false,
        screenshots: false,
        window: false,
      });
    if (path.endsWith("/server/ticks")) return json({ available: false });
    return json({ error: { code: "NOT_FOUND", message: "missing" } }, 404);
  };
  const runtime = createConnectionRuntime({
    ...profile,
    id: "dedicated-probe",
  });
  try {
    const probe = await runtime.probe();
    assert.equal(probe.ok, true);
    assert.equal(probe.capabilities.mode, "dedicated");
    assert.equal(probe.capabilities.client, "unavailable");
    runtime.leases.adopt("tick-control", "tick-lease", Date.now() + 60_000);
    runtime.leases.adopt("input", "input-lease", Date.now() + 60_000);
    worldPhase = "NONE";
    worldSessionId = undefined;
    await runtime.probe();
    assert.equal(runtime.leases.getState("tick-control").state, "idle");
    assert.equal(runtime.leases.getState("input").state, "ready");
  } finally {
    runtime.dispose();
    globalThis.fetch = old;
  }
});

test("event streams honor server filters and only explicit comments signal gaps", async () => {
  const old = globalThis.fetch;
  let requested: URL | undefined;
  const encoder = new TextEncoder();
  const frames = [40, 42]
    .map(
      (seq) =>
        `id: ${seq}\nevent: block.changed\ndata: ${JSON.stringify({ seq, type: "block.changed", worldSessionId: "world-a" })}\n\n`,
    )
    .join("");
  globalThis.fetch = async (input) => {
    requested = new URL(String(input));
    return new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode(frames));
          controller.close();
        },
      }),
      { headers: { "content-type": "text/event-stream" } },
    );
  };
  const runtime = createConnectionRuntime({
    ...profile,
    id: "filtered-events",
  });
  const received: RuntimeEvent[] = [];
  let unsubscribe = () => {};
  try {
    await new Promise<void>((resolve) => {
      unsubscribe = runtime.subscribeEvents(
        { cursor: 37, types: "block.changed", world: "world-a" },
        (event) => {
          received.push(event);
          if (received.filter((item) => !item.gap).length === 2) resolve();
        },
      );
    });
    unsubscribe();
    assert.equal(requested?.searchParams.get("cursor"), "37");
    assert.equal(requested?.searchParams.get("types"), "block.changed");
    assert.equal(requested?.searchParams.get("world"), "world-a");
    assert.deepEqual(
      received.map((event) => event.gap ?? false),
      [false, false],
    );
  } finally {
    unsubscribe();
    runtime.dispose();
    globalThis.fetch = old;
  }
});

test("a client attached to a remote world cannot advertise integrated-server LAN controls", () => {
  const capability = classifyCapabilities({
    serverStatus: { running: false },
    worldInfo: {
      phase: "ACTIVE",
      capabilities: [],
      tickControl: false,
      worldQueries: true,
      commands: true,
    },
    clientInfo: {
      bridgeId: "client",
      capabilities: [],
      input: true,
      screenshots: true,
      window: true,
    },
  });
  assert.equal(capability.mode, "remote");
  assert.equal(
    operationAvailability({ path: "/api/v1/server/lan" }, capability).available,
    false,
  );
});
