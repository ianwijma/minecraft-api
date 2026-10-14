"use client";

import { useEffect, useRef, useState } from "react";
import { operations } from "@/generated/operations";
import { redactValue } from "@/lib/redaction";
import { operationAvailability } from "@/lib/capabilities";
import { formSchema, initializeFormValues } from "@/lib/forms";
import type { ConnectionRuntime } from "@/lib/runtime";
import type { CapabilityContext, RunRecord } from "@/lib/types";
import { clipDuration, MAX_TICK, newSequence, parseSequences, playSequence, scheduledClips, sequenceEnd, serializeSequences, validateSequence, type TimelineClip, type TimelineSequence } from "@/lib/timeline";
import { SchemaFields } from "./SchemaFields";
import { PayloadViewer } from "./PayloadViewer";

interface Props {
  connectionId: string;
  runtime: ConnectionRuntime;
  capabilities: CapabilityContext;
  onRecord(record: RunRecord): void;
}
const PX_PER_TICK = 4;
export default function TimelineEditor({ connectionId, runtime, capabilities, onRecord }: Props) {
  const storageKey = `mapi.dashboard.sequences.${connectionId}`;
  const draftKey = `${storageKey}.draft`;
  const [sequence, setSequence] = useState<TimelineSequence>(newSequence);
  const [library, setLibrary] = useState<TimelineSequence[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [targetTrack, setTargetTrack] = useState("");
  const [tick, setTick] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [message, setMessage] = useState("");
  const [confirmed, setConfirmed] = useState(false);
  const [streamEvents, setStreamEvents] = useState<unknown[]>([]);
  const [results, setResults] = useState<{ clipId: string; record: RunRecord }[]>([]);
  const controller = useRef<AbortController | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const mounted = useRef(true);
  const resultCallback = useRef(onRecord);
  resultCallback.current = onRecord;
  useEffect(() => {
    mounted.current = true;
    try {
      setLibrary(parseSequences(localStorage.getItem(storageKey) ?? "[]"));
      const drafts = parseSequences(localStorage.getItem(draftKey) ?? "[]");
      if (drafts[0]) setSequence(drafts[0]);
    } catch { setMessage("Saved timeline data could not be loaded. You can create a new sequence."); }
    setLoaded(true);
    const stopWhenHidden = () => { if (document.hidden) controller.current?.abort(); };
    document.addEventListener("visibilitychange", stopWhenHidden);
    return () => { mounted.current = false; controller.current?.abort(); document.removeEventListener("visibilitychange", stopWhenHidden); };
  }, [storageKey, draftKey]);
  useEffect(() => {
    if (!loaded) return;
    try { localStorage.setItem(draftKey, serializeSequences([sequence], runtime.profile.token)); }
    catch { setMessage("Browser storage is unavailable or full. Export your sequence to keep it."); }
    setConfirmed(false);
  }, [sequence, loaded, draftKey, runtime.profile.token]);
  const clips = scheduledClips(sequence);
  const selected = clips.find((clip) => clip.id === selectedId);
  const operation = operations.find((item) => item.id === selected?.operationId);
  const selectedTrack = sequence.tracks.find((track) => track.clips.some((clip) => clip.id === selectedId));
  const end = sequenceEnd(sequence);
  const visibleTicks = Math.min(MAX_TICK, Math.max(200, Math.ceil((end + 40) / 20) * 20));
  const updateClip = (id: string, patch: Partial<TimelineClip>) => setSequence((old) => ({ ...old, tracks: old.tracks.map((track) => ({ ...track, clips: track.clips.map((clip) => clip.id === id ? { ...clip, ...patch } : clip) })) }));
  const addClip = (operationId: string, trackId = targetTrack || sequence.tracks[0]?.id, startTick = Math.round(tick)) => {
    const action = operations.find((item) => item.id === operationId);
    if (!action || !trackId || playing) return;
    const clip: TimelineClip = { id: crypto.randomUUID(), operationId, startTick: Math.max(0, Math.min(MAX_TICK, startTick)), values: initializeFormValues(formSchema(action)) };
    setSequence((old) => ({ ...old, tracks: old.tracks.map((track) => track.id === trackId ? { ...track, clips: [...track.clips, clip] } : track) }));
    setSelectedId(clip.id);
  };
  const save = () => {
    const next = [...library.filter((item) => item.id !== sequence.id), sequence];
    try { localStorage.setItem(storageKey, serializeSequences(next, runtime.profile.token)); setLibrary(parseSequences(serializeSequences(next, runtime.profile.token))); setMessage("Sequence saved in this browser."); }
    catch { setMessage("Could not save. Export the sequence to keep a copy."); }
  };
  const download = () => {
    const url = URL.createObjectURL(new Blob([serializeSequences([sequence], runtime.profile.token)], { type: "application/json" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = "mapi-sequence.json"; anchor.click(); URL.revokeObjectURL(url);
  };
  const play = async () => {
    if (controller.current) return;
    const ctl = new AbortController(); controller.current = ctl;
    setPlaying(true); setResults([]); setStreamEvents([]); setMessage("Preparing playback…");
    const subscriptions: (() => void)[] = [];
    try {
      validateSequence(sequence, operations);
      for (const clip of clips) {
        const action = operations.find((item) => item.id === clip.operationId)!;
        const availability = operationAvailability(action, capabilities);
        if (!availability.available) throw new Error(`${action.label}: ${availability.reason}`);
        if (action.security.destructive && !confirmed) throw new Error("Review and confirm destructive actions before playing.");
      }
      for (const topic of ["input", "tick-control"] as const) {
        if (clips.some((clip) => {
          const action = operations.find((item) => item.id === clip.operationId)!;
          return action.security.requiresLease && (action.path.includes("/ticks/") || action.path.includes("/lan") ? "tick-control" : "input") === topic;
        })) {
          await runtime.leases.acquireExplicit(topic, 300, ctl.signal);
          const lease = runtime.leases.getState(topic);
          if (lease.state !== "ready" || lease.expiresAt < Date.now() + end * 50 + 10_000)
            throw new Error("The current lease expires before this sequence ends. Retry after it expires, or shorten the sequence.");
        }
      }
      if (ctl.signal.aborted) throw new DOMException("Playback stopped", "AbortError");
      setTick(0); setMessage("Playing sequence…");
      await playSequence(structuredClone(sequence), {
        signal: ctl.signal,
        onTick: (next) => { if (mounted.current) setTick(next); },
        execute: async (clip) => {
          const action = operations.find((item) => item.id === clip.operationId)!;
          if (action.id === "streamEvents") {
            subscriptions.push(runtime.subscribeEvents({ ...clip.values, scheduled: true }, (event) => { if (mounted.current) setStreamEvents((old) => [...old.slice(-49), redactValue(event, new WeakSet(), runtime.profile.token)]); }, (error) => { if (mounted.current) setMessage(String(error)); ctl.abort(); }));
            return { id: crypto.randomUUID(), operationId: action.id, values: clip.values, saved: false, at: new Date().toISOString(), request: { method: "GET", path: action.path }, response: { streamSubscription: "started for playback" } };
          }
          return runtime.execute(action, action.security.destructive ? { ...clip.values, confirm: true } : clip.values, { signal: ctl.signal, scheduled: true, confirmDestructive: confirmed, timeoutMs: Math.max(15_000, clipDuration(clip) * 50 + 15_000) });
        },
        onResult: (clip, record) => {
          if (!mounted.current) return;
          setResults((old) => [...old, { clipId: clip.id, record }]); resultCallback.current(record);
        },
      });
      if (mounted.current) setMessage("Sequence finished. Accepted API calls may still be running in Minecraft.");
    } catch (error) {
      if (mounted.current) setMessage(ctl.signal.aborted ? "Playback stopped. Already dispatched actions may continue until their bounded duration ends." : error instanceof Error ? error.message : String(error));
    } finally {
      ctl.abort(); subscriptions.forEach((unsubscribe) => unsubscribe()); controller.current = null;
      if (mounted.current) { setPlaying(false); setConfirmed(false); }
    }
  };
  const moveTrack = (id: string, delta: number) => setSequence((old) => {
    const tracks = [...old.tracks]; const index = tracks.findIndex((track) => track.id === id);
    if (index + delta < 0 || index + delta >= tracks.length) return old;
    [tracks[index], tracks[index + delta]] = [tracks[index + delta], tracks[index]];
    return { ...old, tracks };
  });
  const destructive = clips.filter((clip) => operations.find((item) => item.id === clip.operationId)?.security.destructive);
  if (!loaded) return <section aria-label="Timeline editor" aria-busy="true">Loading timeline…</section>;
  return <section className="timeline-editor" aria-label="Timeline editor">
    <div className="timeline-heading"><div><div className="eyebrow">Sequence editor</div><h2>Compose your next move</h2><p>Named tracks, one shared clock. 1 second = 20 Minecraft ticks.</p></div><span className="badge">{clips.length} actions · {(end / 20).toFixed(2)} seconds</span></div>
    <div className="timeline-controls">
      <label>Sequence name<input className="text-input" value={sequence.name} disabled={playing} onChange={(event) => setSequence({ ...sequence, name: event.target.value })} /></label>
      <label>Saved sequences<select aria-label="Saved sequences" className="text-input" value="" disabled={playing} onChange={(event) => { const item = library.find((saved) => saved.id === event.target.value); if (item) { setSequence(structuredClone(item)); setSelectedId(null); setTick(0); } }}><option value="">Load a sequence…</option>{library.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <button className="button small" disabled={playing} onClick={() => { setSequence(newSequence()); setSelectedId(null); setTick(0); }}>New</button>
      <button className="button small" disabled={playing} onClick={save}>Save</button>
      <button className="button small" disabled={playing} onClick={() => { setSequence({ ...structuredClone(sequence), id: crypto.randomUUID(), name: `${sequence.name} copy` }); }}>Duplicate</button>
      <button className="button small" disabled={playing || !library.some((item) => item.id === sequence.id)} onClick={() => { const next = library.filter((item) => item.id !== sequence.id); try { localStorage.setItem(storageKey, serializeSequences(next, runtime.profile.token)); setLibrary(next); setMessage("Saved sequence deleted. The current draft remains open."); } catch { setMessage("Could not delete the saved sequence."); } }}>Delete saved</button>
      <button className="button small" onClick={download}>Export</button><button className="button small" disabled={playing} onClick={() => fileInput.current?.click()}>Import</button>
      <input ref={fileInput} type="file" accept="application/json,.json" hidden aria-label="Import timeline sequence" onChange={async (event) => { const file = event.target.files?.[0]; event.target.value = ""; if (!file) return; try { if (file.size > 1024 * 1024) throw new Error("Sequence file is too large."); const imported = parseSequences(await file.text())[0]; if (!imported) throw new Error("No sequence in this file."); setSequence({ ...imported, id: crypto.randomUUID() }); setSelectedId(null); setTick(0); setMessage("Sequence imported. Review its actions before playing."); } catch (error) { setMessage(String(error)); } }} />
    </div>
    <div className="timeline-layout">
      <aside className="timeline-library" aria-label="Action library"><h3>Actions</h3><input className="text-input" type="search" placeholder="Find an action…" aria-label="Search timeline actions" value={search} onChange={(event) => setSearch(event.target.value)} />
        <label>Add to track<select aria-label="Add to track" className="text-input" value={sequence.tracks.some((track) => track.id === targetTrack) ? targetTrack : sequence.tracks[0]?.id ?? ""} disabled={playing} onChange={(event) => setTargetTrack(event.target.value)}>{sequence.tracks.map((track) => <option key={track.id} value={track.id}>{track.name}</option>)}</select></label>
        <p>Click to add at the playhead, or drag onto a track.</p>
        <div className="timeline-action-list">{operations.filter((action) => `${action.label} ${action.group} ${action.id}`.toLowerCase().includes(search.toLowerCase())).map((action) => { const availability = operationAvailability(action, capabilities); return <button key={action.id} className="timeline-library-action" disabled={playing || !availability.available || !sequence.tracks.length} title={availability.reason ?? action.summary} draggable={!playing && availability.available} onDragStart={(event) => event.dataTransfer.setData("application/mapi-action", action.id)} onClick={() => addClip(action.id)}><span>{action.label}</span><small>{action.group}</small></button>; })}</div>
      </aside>
      <div className="timeline-workspace">
        <div className="timeline-transport"><button className="button primary" disabled={playing || !clips.length} onClick={() => void play()}>{playing ? "Playing…" : "▶ Play from start"}</button><button className="button" disabled={!playing} onClick={() => controller.current?.abort()}>■ Stop</button><span className="timeline-time">{(tick / 20).toFixed(2)}s / {Math.floor(tick)} ticks</span><button className="button small" disabled={playing || sequence.tracks.length >= 50} onClick={() => setSequence({ ...sequence, tracks: [...sequence.tracks, { id: crypto.randomUUID(), name: `Track ${sequence.tracks.length + 1}`, clips: [] }] })}>+ Track</button></div>
        <div className="timeline-scroll"><div className="timeline-canvas" style={{ width: 180 + visibleTicks * PX_PER_TICK }}>
          <div className="timeline-ruler"><span className="timeline-track-label">Seconds / ticks</span><div className="timeline-ruler-body" onClick={(event) => { if (!playing) setTick(Math.round((event.clientX - event.currentTarget.getBoundingClientRect().left) / PX_PER_TICK)); }}>{Array.from({ length: visibleTicks / 20 + 1 }, (_, i) => <span key={i} style={{ left: i * 20 * PX_PER_TICK }}>{i}s<small>{i * 20}</small></span>)}</div></div>
          {sequence.tracks.map((track, index) => {
            const rowEnds: number[] = [];
            const rows = new Map<string, number>();
            for (const clip of [...track.clips].sort((a, b) => a.startTick - b.startTick)) {
              const left = clip.startTick * PX_PER_TICK;
              let row = rowEnds.findIndex((right) => right + 4 <= left);
              if (row < 0) row = rowEnds.length;
              rowEnds[row] = left + Math.max(72, clipDuration(clip) * PX_PER_TICK);
              rows.set(clip.id, row);
            }
            return <div className="timeline-track" key={track.id}><div className="timeline-track-label"><input aria-label={`Track ${index + 1} name`} value={track.name} disabled={playing} onChange={(event) => setSequence({ ...sequence, tracks: sequence.tracks.map((item) => item.id === track.id ? { ...item, name: event.target.value } : item) })} /><div><button disabled={playing || index === 0} aria-label={`Move ${track.name} up`} onClick={() => moveTrack(track.id, -1)}>↑</button><button disabled={playing || index === sequence.tracks.length - 1} aria-label={`Move ${track.name} down`} onClick={() => moveTrack(track.id, 1)}>↓</button><button disabled={playing || sequence.tracks.length === 1} aria-label={`Delete ${track.name}`} onClick={() => setSequence({ ...sequence, tracks: sequence.tracks.filter((item) => item.id !== track.id) })}>×</button></div></div>
            <div className="timeline-lane" style={{ minHeight: Math.max(84, rowEnds.length * 68 + 16) }} onDragOver={(event) => { if (!playing) event.preventDefault(); }} onDrop={(event) => { event.preventDefault(); if (playing) return; const startTick = Math.max(0, Math.min(MAX_TICK, Math.round((event.clientX - event.currentTarget.getBoundingClientRect().left) / PX_PER_TICK))); const id = event.dataTransfer.getData("application/mapi-clip"); if (id) { setSequence((old) => { const clip = scheduledClips(old).find((item) => item.id === id); if (!clip) return old; return { ...old, tracks: old.tracks.map((item) => ({ ...item, clips: [...item.clips.filter((c) => c.id !== id), ...(item.id === track.id ? [{ ...clip, startTick }] : [])] })) }; }); } else addClip(event.dataTransfer.getData("application/mapi-action"), track.id, startTick); }}>
              {track.clips.map((clip) => { const action = operations.find((item) => item.id === clip.operationId); const result = results.find((item) => item.clipId === clip.id); return <button type="button" key={clip.id} draggable={!playing} onDragStart={(event) => event.dataTransfer.setData("application/mapi-clip", clip.id)} aria-pressed={selectedId === clip.id} className={`timeline-clip${selectedId === clip.id ? " selected" : ""}${result?.record.error ? " failed" : result ? " dispatched" : ""}`} style={{ top: 12 + (rows.get(clip.id) ?? 0) * 68, left: clip.startTick * PX_PER_TICK, width: Math.max(72, clipDuration(clip) * PX_PER_TICK) }} onClick={() => setSelectedId(clip.id)} title={`${action?.label ?? clip.operationId} · ${clip.startTick} ticks`}><strong>{action?.label ?? clip.operationId}</strong><small>{clip.startTick / 20}s{clipDuration(clip) ? ` · ${clipDuration(clip)} ticks` : " · ◆"}</small></button>; })}
            </div></div>; })}
          <div className="timeline-playhead" style={{ left: 180 + tick * PX_PER_TICK }} aria-hidden="true" />
        </div></div>
        <p className="timeline-hint">Drag clips to move them. Select a clip to edit its start tick and parameters. Overlapping actions share the clock; API calls are dispatched separately.</p>
        {destructive.length > 0 && <label className="timeline-confirm"><input type="checkbox" checked={confirmed} disabled={playing} onChange={(event) => setConfirmed(event.target.checked)} /> I confirm this run includes: {destructive.map((clip) => operations.find((action) => action.id === clip.operationId)?.label).join(", ")}.</label>}
        <p className="notice" role="status">{message || "Keep this page visible during playback. Stop cancels future calls; bounded game actions may continue."}</p>
      </div>
      <aside className="timeline-inspector" aria-label="Clip inspector"><h3>{operation?.label ?? "Clip inspector"}</h3>{selected && operation ? <fieldset disabled={playing}><p>{operation.summary}</p><label>Start tick<input className="text-input" type="number" min={0} max={MAX_TICK} step={1} value={selected.startTick} onChange={(event) => updateClip(selected.id, { startTick: Number(event.target.value) })} /></label><p>{(selected.startTick / 20).toFixed(2)} seconds{clipDuration(selected) > 0 ? ` · duration ${(clipDuration(selected) / 20).toFixed(2)} seconds` : ""}</p>
        <label>Track<select aria-label="Track" className="text-input" value={selectedTrack?.id ?? ""} onChange={(event) => { const trackId = event.target.value; setSequence((old) => ({ ...old, tracks: old.tracks.map((track) => ({ ...track, clips: [...track.clips.filter((clip) => clip.id !== selected.id), ...(track.id === trackId ? [selected] : [])] })) })); }}>{sequence.tracks.map((track) => <option key={track.id} value={track.id}>{track.name}</option>)}</select></label>
        <SchemaFields key={selected.id} operation={operation} initialValues={selected.values} onValuesChange={(values) => updateClip(selected.id, { values })} />
        <div className="timeline-inspector-buttons"><button className="button small" onClick={() => { const copy = { ...structuredClone(selected), id: crypto.randomUUID(), startTick: Math.min(MAX_TICK, selected.startTick + Math.max(1, clipDuration(selected))) }; setSequence((old) => ({ ...old, tracks: old.tracks.map((track) => track.id === selectedTrack?.id ? { ...track, clips: [...track.clips, copy] } : track) })); setSelectedId(copy.id); }}>Duplicate clip</button><button className="button small danger" onClick={() => { setSequence({ ...sequence, tracks: sequence.tracks.map((track) => ({ ...track, clips: track.clips.filter((clip) => clip.id !== selected.id) })) }); setSelectedId(null); }}>Delete clip</button></div>
      </fieldset> : <p>Select an action on the timeline to edit it.</p>}</aside>
    </div>
    {streamEvents.length > 0 && <details className="timeline-results"><summary>Stream events · latest {streamEvents.length}</summary><pre>{JSON.stringify(streamEvents, null, 2)}</pre></details>}
    {results.length > 0 && <details className="timeline-results" open><summary>Playback results · {results.length} dispatched actions</summary>{results.map(({ clipId, record }) => <article key={record.id}><h4>{operations.find((action) => action.id === record.operationId)?.label} · {record.error ? "Failed" : record.status === 202 ? "Accepted" : "Dispatched"}</h4><PayloadViewer record={record} operation={operations.find((action) => action.id === record.operationId)!} /><small>Clip {clipId}</small></article>)}</details>}
  </section>;
}
