import type { Operation } from "./catalog.ts";
import { formSchema, validateFormValue } from "./forms.ts";
import { omitTransient } from "./storage.ts";
import { redactValue } from "./redaction.ts";
import type { RunRecord } from "./types.ts";

export const TICKS_PER_SECOND = 20;
export const MAX_TICK = 4800;
export interface TimelineClip {
  id: string;
  operationId: string;
  startTick: number;
  values: Record<string, unknown>;
}
export interface TimelineTrack { id: string; name: string; clips: TimelineClip[] }
export interface TimelineSequence { version: 1; id: string; name: string; tracks: TimelineTrack[] }
export function newSequence(): TimelineSequence {
  return { version: 1, id: crypto.randomUUID(), name: "Untitled sequence", tracks: [{ id: crypto.randomUUID(), name: "Track 1", clips: [] }] };
}
export function clipDuration(clip: TimelineClip): number {
  return clip.operationId === "holdKey" && Number.isInteger(clip.values.ticks) ? Number(clip.values.ticks) : 0;
}
export function scheduledClips(sequence: TimelineSequence): TimelineClip[] {
  return sequence.tracks.flatMap((track) => track.clips).sort((a, b) => a.startTick - b.startTick);
}
export function sequenceEnd(sequence: TimelineSequence): number {
  return Math.max(20, ...scheduledClips(sequence).map((clip) => clip.startTick + clipDuration(clip)));
}
export function validateSequence(sequence: TimelineSequence, catalog: readonly Operation[]): void {
  if (!sequence.tracks.length) throw new Error("Add a track first.");
  const clips = scheduledClips(sequence);
  if (!clips.length) throw new Error("Add at least one action to the timeline.");
  if (clips.length > 200) throw new Error("A sequence supports up to 200 actions.");
  if (sequenceEnd(sequence) > MAX_TICK) throw new Error("Sequences can be up to 240 seconds long.");
  let holdEnd = -1;
  for (const clip of clips) {
    if (clip.operationId === "holdKey") {
      if (clip.startTick < holdEnd) throw new Error("Key holds cannot overlap: the current Minecraft API executes one key hold at a time. Move the clips apart.");
      holdEnd = clip.startTick + clipDuration(clip);
    }
    if (!Number.isInteger(clip.startTick) || clip.startTick < 0 || clip.startTick > MAX_TICK) throw new Error("Clip start must be a whole tick between 0 and 4800.");
    const operation = catalog.find((op) => op.id === clip.operationId);
    if (!operation) throw new Error(`Unknown operation: ${clip.operationId}`);
    const values = operation.security.destructive ? { ...clip.values, confirm: true } : clip.values;
    validateFormValue(formSchema(operation), values, operation.label);
  }
}
export function serializeSequences(sequences: TimelineSequence[], token?: string): string {
  return JSON.stringify(sequences.map((sequence) => ({ ...sequence, tracks: sequence.tracks.map((track) => ({ ...track, clips: track.clips.map((clip) => ({ ...clip, values: redactValue(omitTransient(clip.values), new WeakSet(), token) })) })) })));
}
export function parseSequences(raw: string): TimelineSequence[] {
  const data: unknown = JSON.parse(raw);
  if (!Array.isArray(data) || data.length > 100) throw new Error("Invalid sequence library.");
  const ids = new Set<string>();
  for (const sequence of data) {
    if (!sequence || sequence.version !== 1 || typeof sequence.id !== "string" || typeof sequence.name !== "string" || !Array.isArray(sequence.tracks) || sequence.tracks.length > 50) throw new Error("Invalid sequence.");
    const localIds = new Set<string>();
    if (ids.has(sequence.id)) throw new Error("Duplicate sequence ID.");
    ids.add(sequence.id);
    for (const track of sequence.tracks) {
      if (!track || typeof track.id !== "string" || localIds.has(track.id) || typeof track.name !== "string" || !Array.isArray(track.clips) || track.clips.length > 200) throw new Error("Invalid track.");
      localIds.add(track.id);
      for (const clip of track.clips) {
        if (!clip || typeof clip.id !== "string" || localIds.has(clip.id) || typeof clip.operationId !== "string" || !Number.isInteger(clip.startTick) || clip.startTick < 0 || clip.startTick > MAX_TICK || !clip.values || typeof clip.values !== "object" || Array.isArray(clip.values)) throw new Error("Invalid action clip.");
        localIds.add(clip.id);
      }
    }
  }
  return JSON.parse(serializeSequences(data));
}

export interface PlaybackClock {
  now(): number;
  wait(ms: number, signal: AbortSignal): Promise<void>;
}
const browserClock: PlaybackClock = {
  now: () => performance.now(),
  wait: (ms, signal) => new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException("Playback stopped", "AbortError")); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    signal.addEventListener("abort", abort, { once: true });
    if (signal.aborted) abort();
  }),
};
export async function playSequence(sequence: TimelineSequence, options: {
  signal: AbortSignal;
  execute(clip: TimelineClip): Promise<RunRecord>;
  onTick(tick: number): void;
  onResult(clip: TimelineClip, result: RunRecord): void;
  clock?: PlaybackClock;
}): Promise<void> {
  const clock = options.clock ?? browserClock;
  const clips = scheduledClips(sequence);
  const start = clock.now();
  const pending: Promise<void>[] = [];
  let index = 0;
  let failure: Error | undefined;
  const check = () => {
    if (options.signal.aborted) throw new DOMException("Playback stopped", "AbortError");
    if (failure) throw failure;
  };
  try {
    while (true) {
      check();
      const elapsed = clock.now() - start;
      options.onTick(Math.min(sequenceEnd(sequence), elapsed / 50));
      while (index < clips.length && clips[index].startTick * 50 <= elapsed) {
        const clip = clips[index++];
        if (elapsed - clip.startTick * 50 > 250) throw new Error("Playback stopped because browser timing fell behind. Keep this tab visible.");
        check();
        pending.push(options.execute(clip).then((result) => {
          options.onResult(clip, result);
          if (result.error) failure = new Error(result.error.message);
        }).catch((error) => { failure = error instanceof Error ? error : new Error(String(error)); }));
      }
      if (index === clips.length && elapsed >= sequenceEnd(sequence) * 50) break;
      await clock.wait(16, options.signal);
    }
    await Promise.all(pending);
    check();
  } finally {
    // Callers abort outstanding requests on failure; observe every dispatched promise.
    void Promise.all(pending);
  }
}
