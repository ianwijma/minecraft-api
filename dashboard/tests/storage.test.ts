import { test } from "node:test";
import assert from "node:assert/strict";
import {
  clearSavedCredentials,
  importProfiles,
  loadPresets,
  loadProfiles,
  removeProfile,
  savePresets,
  saveProfiles,
  saveSessionHistory,
  loadSessionHistory,
  type PersistedState,
} from "../src/lib/storage.ts";

class MemoryStorage implements Storage {
  private data = new Map<string, string>();
  get length() {
    return this.data.size;
  }
  clear() {
    this.data.clear();
  }
  getItem(key: string) {
    return this.data.get(key) ?? null;
  }
  key(index: number) {
    return [...this.data.keys()][index] ?? null;
  }
  removeItem(key: string) {
    this.data.delete(key);
  }
  setItem(key: string, value: string) {
    if (value.length > 200_000)
      throw new DOMException("full", "QuotaExceededError");
    this.data.set(key, String(value));
  }
}

test("profile persistence honors token optout and malformed storage", () => {
  const storage = new MemoryStorage();
  const initial: PersistedState = {
    version: 1,
    profiles: [
      {
        id: "a",
        name: "Local",
        baseUrl: "http://127.0.0.1:25586",
        token: "secret-value",
        rememberToken: false,
      },
    ],
    selectedId: "a",
    saveTokens: true,
  };
  assert.equal(saveProfiles(initial, storage), true);
  assert.equal(
    JSON.stringify(storage.getItem("mapi.dashboard.profiles")).includes(
      "secret-value",
    ),
    false,
  );
  assert.equal(loadProfiles(storage)?.profiles[0]?.token, undefined);
  assert.equal(clearSavedCredentials(storage), true);
  storage.setItem("mapi.dashboard.profiles", "{ broken");
  assert.equal(loadProfiles(storage), null);
});

test("history is bounded to session storage and profile removal clears local and session state", () => {
  const local = new MemoryStorage(),
    session = new MemoryStorage();
  const profiles: PersistedState = {
    version: 1,
    profiles: [
      {
        id: "a",
        name: "Local",
        baseUrl: "http://127.0.0.1:25586",
        rememberToken: false,
      },
    ],
    selectedId: "a",
    saveTokens: true,
  };
  saveProfiles(profiles, local);
  savePresets(
    "a",
    [
      {
        id: "preset",
        operationId: "op",
        values: { leaseId: "stale", x: 1 },
        savedAt: "now",
      },
    ],
    local,
  );
  assert.equal(loadPresets("a", local)[0]?.values.leaseId, undefined);
  savePresets(
    "a",
    [
      {
        id: "saved",
        operationId: "op",
        values: { confirm: true, worldSessionId: "stale", x: 2 },
        savedAt: "now",
        run: {
          id: "r",
          operationId: "op",
          values: {
            confirm: true,
            worldSessionId: "stale",
            authorization: "secret-token",
          },
          saved: true,
          at: "now",
          request: {
            method: "POST",
            path: "/api/v1/test",
            body: { confirm: true },
          },
          response: { sensitive: "session-only" },
          error: { code: "SECRET", message: "session-only" },
          status: 200,
          durationMs: 2,
        },
      },
    ],
    local,
  );
  const stored = local.getItem("mapi.dashboard.presets.a")!;
  assert.equal(stored.includes("session-only"), false);
  assert.equal(stored.includes("confirm"), false);
  assert.equal(stored.includes("secret-token"), false);
  const records = Array.from({ length: 60 }, (_, i) => ({
    id: String(i),
    operationId: "op",
    values: {},
    saved: false,
    at: "now",
    request: { method: "GET", path: "/api/v1/health" },
  }));
  assert.equal(saveSessionHistory("a", records, session), true);
  assert.equal(loadSessionHistory(session).a?.length, 50);
  removeProfile("a", profiles, local, session);
  assert.equal(local.getItem("mapi.dashboard.presets.a"), null);
  assert.equal(loadSessionHistory(session).a, undefined);
});

test("storage quota errors return failure without throwing", () => {
  const broken = {
    ...new MemoryStorage(),
    setItem() {
      throw new DOMException("full", "QuotaExceededError");
    },
  } as Storage;
  assert.equal(
    saveProfiles(
      { version: 1, profiles: [], selectedId: null, saveTokens: true },
      broken,
    ),
    false,
  );
});

test("unsupported versions and malformed history/presets are ignored safely", () => {
  const local = new MemoryStorage(),
    session = new MemoryStorage();
  local.setItem(
    "mapi.dashboard.profiles",
    JSON.stringify({
      version: 99,
      profiles: [{ id: "future", name: "Future", baseUrl: "http://localhost" }],
    }),
  );
  assert.equal(loadProfiles(local), null);
  assert.throws(
    () => importProfiles(JSON.stringify({ version: 99, profiles: [] })),
    /unsupported profile export version/,
  );
  local.setItem(
    "mapi.dashboard.presets.a",
    JSON.stringify({
      version: 1,
      presets: [
        null,
        {},
        { id: "ok", operationId: "x", values: {}, savedAt: "now" },
        {
          id: "bad-run",
          operationId: "x",
          values: {},
          savedAt: "now",
          run: { id: 2 },
        },
      ],
    }),
  );
  assert.deepEqual(
    loadPresets("a", local).map((p) => p.id),
    ["ok"],
  );
  session.setItem(
    "mapi.dashboard.history",
    JSON.stringify({
      a: [
        null,
        {},
        {
          id: "valid",
          operationId: "x",
          values: {},
          saved: false,
          at: "now",
          request: { method: "GET", path: "/api/v1/health" },
        },
        {
          id: "bad",
          operationId: "x",
          values: [],
          saved: false,
          at: "now",
          request: { method: "DELETE", path: "/x" },
        },
      ],
      broken: "not-an-array",
    }),
  );
  assert.deepEqual(
    loadSessionHistory(session).a?.map((r) => r.id),
    ["valid"],
  );
  assert.equal(loadSessionHistory(session).broken, undefined);
});
