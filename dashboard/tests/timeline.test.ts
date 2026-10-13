import { test } from "node:test";
import assert from "node:assert/strict";
import { operations } from "../src/generated/operations.ts";
import { parseSequences, playSequence, serializeSequences, validateSequence, type TimelineSequence, type PlaybackClock } from "../src/lib/timeline.ts";
import type { RunRecord } from "../src/lib/types.ts";
const sequence: TimelineSequence = { version: 1, id: "s", name: "Test", tracks: [
  { id: "a", name: "Movement", clips: [{ id: "later", operationId: "getHealth", startTick: 20, values: {} }] },
  { id: "b", name: "Camera", clips: [{ id: "first", operationId: "getInfo", startTick: 0, values: {} }, { id: "same", operationId: "getHealth", startTick: 20, values: {} }] },
] };
function record(id: string): RunRecord { return { id, operationId: "getHealth", values: {}, saved: false, at: "", request: { method: "GET", path: "/api/v1/health" } }; }
function clock(): PlaybackClock { let now = 0; return { now: () => now, wait: async (ms) => { now += ms; } }; }
test("multitrack playback shares a 20 ticks per second clock without awaiting earlier actions", async () => {
  const time = clock(); const calls: [string, number][] = []; let release!: () => void;
  const blocked = new Promise<void>((resolve) => { release = resolve; });
  await playSequence(sequence, { signal: new AbortController().signal, clock: time, onTick: () => {}, onResult: () => {}, execute: async (clip) => {
    calls.push([clip.id, time.now()]);
    if (clip.id === "first") await blocked;
    if (clip.id === "same") release();
    return record(clip.id);
  } });
  assert.deepEqual(calls.map(([id]) => id), ["first", "later", "same"]);
  assert.equal(calls[0][1], 0); assert.ok(calls[1][1] >= 1000 && calls[1][1] < 1050); assert.equal(calls[1][1], calls[2][1]);
});
test("stop and API errors prevent later scheduled actions", async () => {
  for (const abort of [true, false]) {
    const ctl = new AbortController(); const calls: string[] = [];
    await assert.rejects(playSequence(sequence, { signal: ctl.signal, clock: clock(), onTick: () => {}, onResult: () => {}, execute: async (clip) => {
      calls.push(clip.id); if (abort) ctl.abort();
      return { ...record(clip.id), error: abort ? undefined : { status: 429, code: "RATE_LIMITED", message: "budget exhausted" } };
    } }));
    assert.deepEqual(calls, ["first"]);
  }
});
test("browser timing drift stops playback instead of bursting overdue commands", async () => {
  let now = 0; const calls: string[] = [];
  await assert.rejects(playSequence(sequence, { signal: new AbortController().signal, clock: { now: () => now, wait: async () => { now += 2000; } }, onTick: () => {}, onResult: () => {}, execute: async (clip) => { calls.push(clip.id); return record(clip.id); } }), /timing fell behind/);
  assert.deepEqual(calls, ["first"]);
});
test("sequence persistence omits credentials, lease identifiers, and confirmation", () => {
  const copy = structuredClone(sequence); copy.tracks[0].clips[0].values = { leaseId: "lease-secret", confirm: true, text: "my-bearer-token" };
  const raw = serializeSequences([copy], "my-bearer-token");
  assert.ok(!raw.includes("lease-secret")); assert.ok(!raw.includes("my-bearer-token")); assert.ok(!raw.includes('"confirm"'));
  assert.equal(parseSequences(raw)[0].tracks[1].name, "Camera");
  assert.throws(() => parseSequences('[{"version":1}]'), /Invalid sequence/);
});
test("validation rejects invalid ticks, unknown actions, and invalid action parameters", () => {
  validateSequence(sequence, operations);
  for (const patch of [{ startTick: -1 }, { startTick: 0.5 }, { operationId: "missing" }, { operationId: "holdKey", values: { keyCode: 87, ticks: 0 } }]) {
    const copy = structuredClone(sequence); Object.assign(copy.tracks[0].clips[0], patch); assert.throws(() => validateSequence(copy, operations));
  }
});

test("key holds on different tracks cannot overlap because the API serializes them", () => {
  const copy = structuredClone(sequence);
  copy.tracks[0].clips = [{ id: "hold-a", operationId: "holdKey", startTick: 0, values: { keyCode: 87, ticks: 40 } }];
  copy.tracks[1].clips = [{ id: "hold-b", operationId: "holdKey", startTick: 20, values: { keyCode: 32, ticks: 20 } }];
  assert.throws(() => validateSequence(copy, operations), /Key holds cannot overlap/);
  copy.tracks[1].clips[0].startTick = 40;
  validateSequence(copy, operations);
});
