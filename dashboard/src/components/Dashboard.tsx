"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { operations } from "@/generated/operations";
import { operationAvailability, emptyCapabilities } from "@/lib/capabilities";
import { requestPreview } from "@/lib/forms";
import { createConnectionRuntime, type ConnectionRuntime } from "@/lib/runtime";
import { redactValue } from "@/lib/redaction";
import { clearSavedCredentials, exportProfiles, importProfiles, loadPresets, loadProfiles, loadSessionHistory, omitTransient, removeProfile, savePresets, saveProfiles, saveSessionHistory, type PersistedState, type Preset } from "@/lib/storage";
import type { CapabilityContext, ConnectionProfile, ProbeResult, RunRecord, RuntimeEvent } from "@/lib/types";
import { ActionModal, clearActionDrafts } from "./ActionModal";
import ConnectionDialog, { type ConnectOutcome } from "./ConnectionDialog";
import RemovalDialog from "./RemovalDialog";
import ResponseInspector from "./ResponseInspector";

const initialState: PersistedState = { version: 1, profiles: [], selectedId: null, saveTokens: true };
type ConnectionState = "disconnected" | "connecting" | "connected" | "error";
const runtimeById = new Map<string, ConnectionRuntime>();
const EMPTY_CAPABILITIES = emptyCapabilities();

function prettyHost(url: string): string {
  try { const parsed = new URL(url); return `${parsed.hostname}${parsed.port ? `:${parsed.port}` : ""}`; }
  catch { return url; }
}

function profileRecords(profileId: string, history: Record<string, RunRecord[]>, profiles: Map<string, Preset[]>): RunRecord[] {
  const recent = history[profileId] ?? [];
  const saved = (profiles.get(profileId) ?? []).map((preset) => {
    if (preset.run) return preset.run;
    const operation = operations.find((item) => item.id === preset.operationId);
    const preview = operation ? requestPreview(operation, preset.values) : undefined;
    const request = operation && preview ? { method: operation.method, path: preview.path as string, ...(preview.query ? { query: preview.query } : {}), ...(preview.body ? { body: preview.body } : {}) } : { method: "", path: "" };
    return { id: preset.id, operationId: preset.operationId, values: preset.values, saved: true, at: preset.savedAt, request } as RunRecord;
  });
  const byId = new Map<string, RunRecord>();
  for (const run of recent) byId.set(run.id, run);
  for (const run of saved) byId.set(run.id, byId.has(run.id) ? { ...byId.get(run.id)!, saved: true } : { ...run, saved: true });
  return [...byId.values()];
}

export default function Dashboard() {
  const [persisted, setPersisted] = useState<PersistedState>(initialState);
  const [ready, setReady] = useState(false);
  const [connectionStates, setConnectionStates] = useState<Record<string, ConnectionState>>({});
  const [capabilityState, setCapabilityState] = useState<Record<string, CapabilityContext>>({});
  const [probeInfo, setProbeInfo] = useState<Record<string, Record<string, unknown>>>({});
  const [records, setRecords] = useState<Record<string, RunRecord[]>>({});
  const [presetMap, setPresetMap] = useState<Record<string, Preset[]>>({});
  const [events, setEvents] = useState<Record<string, RuntimeEvent[]>>({});
  const [streamEnabled, setStreamEnabled] = useState<Record<string, boolean>>({});
  const [streamOptions, setStreamOptions] = useState<Record<string, Record<string, unknown>>>({});
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogProfile, setDialogProfile] = useState<ConnectionProfile | undefined>();
  const [removeTargetId, setRemoveTargetId] = useState<string | null>(null);
  const [action, setAction] = useState<(typeof operations)[number] | null>(null);
  const [search, setSearch] = useState("");
  const [availableOnly, setAvailableOnly] = useState(false);
  const [notice, setNotice] = useState("");
  const [copyNotice, setCopyNotice] = useState("");
  const [refreshingCapabilities, setRefreshingCapabilities] = useState(false);
  const attempt = useRef(0);
  const pendingDialogRuntime = useRef<ConnectionRuntime | null>(null);
  const pendingDialogController = useRef<AbortController | null>(null);
  const currentPersisted = useRef(persisted);
  const currentConnectionStates = useRef(connectionStates);
  const autoProbeStarted = useRef(new Set<string>());
  const runControllers = useRef(new Map<string, Set<AbortController>>());
  const importFileRef = useRef<HTMLInputElement>(null);
  const activityAnchor = useRef<{ id: string; top: number } | null>(null);
  currentPersisted.current = persisted;
  currentConnectionStates.current = connectionStates;

  useEffect(() => {
    const loadedFromStorage = loadProfiles() ?? initialState;
    const loaded: PersistedState = { ...loadedFromStorage, selectedId: loadedFromStorage.selectedId ?? loadedFromStorage.profiles[0]?.id ?? null };
    const savedHistory = loadSessionHistory();
    const nextPresets: Record<string, Preset[]> = {};
    const nextRecords: Record<string, RunRecord[]> = {};
    for (const profile of loaded.profiles) {
      nextPresets[profile.id] = loadPresets(profile.id);
      nextRecords[profile.id] = profileRecords(profile.id, savedHistory, new Map([[profile.id, nextPresets[profile.id]]]));
    }
    setPersisted(loaded);
    setRecords(nextRecords);
    setPresetMap(nextPresets);
    setConnectionStates(Object.fromEntries(loaded.profiles.map((profile) => [profile.id, "disconnected"])));
    setReady(true);
  }, []);

  useEffect(() => {
    if (!ready) return;
    const ok = saveProfiles(persisted);
    if (!ok) setNotice("Browser storage is full or unavailable. This connection may not survive reload.");
  }, [persisted, ready]);

  const activeProfile = persisted.profiles.find((profile) => profile.id === persisted.selectedId);
  const activeRuntime = activeProfile ? runtimeById.get(activeProfile.id) : undefined;
  const activeState = activeProfile ? connectionStates[activeProfile.id] ?? "disconnected" : "disconnected";
  const capabilities = activeProfile ? capabilityState[activeProfile.id] ?? activeRuntime?.capabilities ?? EMPTY_CAPABILITIES : EMPTY_CAPABILITIES;
  const activeRecords = activeProfile ? records[activeProfile.id] ?? [] : [];
  const latestRecord = activeRecords.filter((record) => record.response !== undefined || record.error).sort((a, b) => Date.parse(b.at) - Date.parse(a.at))[0];
  const eventsForActive = activeProfile ? events[activeProfile.id] ?? [] : [];
  const operationById = useMemo(() => new Map(operations.map((operation) => [operation.id, operation])), []);
  const groups = useMemo(() => [...new Set(operations.map((operation) => operation.group))], []);

  const pushEvent = useCallback((id: string, event: RuntimeEvent) => {
    const list = document.querySelector<HTMLElement>("[data-testid=activity-list]");
    const bounds = list?.getBoundingClientRect();
    const visible = bounds && list ? [...list.querySelectorAll<HTMLElement>("[data-event-id]")].find((item) => item.getBoundingClientRect().bottom > bounds.top) : undefined;
    if (visible) activityAnchor.current = { id: visible.dataset.eventId ?? "", top: visible.getBoundingClientRect().top };
    const identified = { ...event, id: crypto.randomUUID() } as RuntimeEvent & { id?: string };
    setEvents((old) => ({ ...old, [id]: [...(old[id] ?? []), identified].slice(-200) }));
  }, []);

  useLayoutEffect(() => {
    const anchor = activityAnchor.current;
    const list = document.querySelector<HTMLElement>("[data-testid=activity-list]");
    if (!anchor || !list) return;
    const element = [...list.querySelectorAll<HTMLElement>("[data-event-id]")].find((item) => item.dataset.eventId === anchor.id);
    if (element) list.scrollTop += element.getBoundingClientRect().top - anchor.top;
    activityAnchor.current = null;
  }, [events]);

  useLayoutEffect(() => {
    const inspector = document.querySelector<HTMLElement>(".inspector");
    if (!inspector) return;
    const measure = () => {
      const mobile = window.matchMedia("(max-width: 900px)").matches;
      const available = window.innerHeight - inspector.getBoundingClientRect().top - (mobile ? 24 : 18);
      const height = mobile ? Math.min(360, Math.max(200, window.innerHeight - 140)) : Math.max(1, available);
      inspector.style.setProperty("--inspector-height", `${Math.round(height)}px`);
    };
    measure();
    window.addEventListener("resize", measure);
    const observer = typeof ResizeObserver === "undefined" ? undefined : new ResizeObserver(measure);
    for (const target of document.querySelectorAll<HTMLElement>(".statusbar,.toolbar")) observer?.observe(target);
    return () => { window.removeEventListener("resize", measure); observer?.disconnect(); };
  }, [activeProfile?.id, activeState, search]);

  useEffect(() => {
    if (!ready || !activeProfile || !activeRuntime || !streamEnabled[activeProfile.id]) return;
    const stop = activeRuntime.subscribeEvents(streamOptions[activeProfile.id] ?? {}, (event) => pushEvent(activeProfile.id, event), (error) => pushEvent(activeProfile.id, { type: `Stream reconnecting · ${error instanceof Error ? error.message : "network error"}` }));
    return stop;
  }, [activeProfile?.id, activeRuntime, pushEvent, ready, streamEnabled, streamOptions]);

  useEffect(() => () => {
    pendingDialogController.current?.abort();
    for (const runtime of runtimeById.values()) runtime.dispose();
    runtimeById.clear();
  }, []);

  const openNew = () => {
    setAction(null);
    pendingDialogController.current?.abort();
    pendingDialogRuntime.current?.dispose(); pendingDialogRuntime.current = null;
    attempt.current++;
    setDialogProfile(undefined);
    setDialogOpen(true);
  };
  const openEdit = () => {
    if (!activeProfile) return;
    setAction(null);
    pendingDialogController.current?.abort();
    pendingDialogRuntime.current?.dispose(); pendingDialogRuntime.current = null;
    attempt.current++;
    setDialogProfile(activeProfile);
    setDialogOpen(true);
  };
  const openDuplicate = () => {
    if (!activeProfile) return;
    setAction(null);
    pendingDialogController.current?.abort();
    pendingDialogRuntime.current?.dispose(); pendingDialogRuntime.current = null;
    attempt.current++;
    setDialogProfile({ ...activeProfile, id: crypto.randomUUID(), name: `${activeProfile.name} copy` });
    setDialogOpen(true);
  };
  const closeDialog = () => {
    attempt.current++;
    pendingDialogController.current?.abort(); pendingDialogController.current = null;
    pendingDialogRuntime.current?.dispose(); pendingDialogRuntime.current = null;
    setDialogOpen(false);
  };

  const probeProfile = useCallback(async (profile: ConnectionProfile, keepOnSuccess: boolean): Promise<ConnectOutcome> => {
    const runtime = createConnectionRuntime(profile);
    const controller = new AbortController();
    pendingDialogController.current?.abort();
    pendingDialogController.current = controller;
    pendingDialogRuntime.current?.dispose(); pendingDialogRuntime.current = runtime;
    const result = await runtime.probe({ signal: controller.signal });
    if (pendingDialogRuntime.current === runtime) pendingDialogRuntime.current = null;
    if (pendingDialogController.current === controller) pendingDialogController.current = null;
    if (!result.ok || !result.authenticated || !result.compatible) {
      runtime.dispose();
      return { ok: false, probe: result, error: result.error?.message ?? "MAPI health, authentication, or protocol compatibility check failed." };
    }
    if (!keepOnSuccess) runtime.dispose();
    return { ok: true, probe: result };
  }, []);

  const connectProfile = useCallback(async (profile: ConnectionProfile, selectOnSuccess = true, guardDialogAttempt = true, shouldCommit?: () => boolean): Promise<ConnectOutcome> => {
    const requestId = attempt.current;
    const hadRuntime = runtimeById.has(profile.id);
    const previousState = currentConnectionStates.current[profile.id] ?? "disconnected";
    if (!hadRuntime) setConnectionStates((old) => ({ ...old, [profile.id]: "connecting" }));
    const runtime = createConnectionRuntime(profile);
    const controller = new AbortController();
    if (guardDialogAttempt) {
      pendingDialogController.current?.abort();
      pendingDialogRuntime.current?.dispose();
      pendingDialogController.current = controller;
      pendingDialogRuntime.current = runtime;
    }
    const result = await runtime.probe({ signal: controller.signal });
    if (pendingDialogRuntime.current === runtime) pendingDialogRuntime.current = null;
    if (pendingDialogController.current === controller) pendingDialogController.current = null;
    if ((guardDialogAttempt && requestId !== attempt.current) || (shouldCommit && !shouldCommit())) {
      runtime.dispose();
      if (!hadRuntime) setConnectionStates((old) => ({ ...old, [profile.id]: previousState }));
      return { ok: false, probe: result, error: "Connection attempt canceled." };
    }
    if (!result.ok || !result.authenticated || !result.compatible) {
      runtime.dispose();
      if (!hadRuntime) setConnectionStates((old) => ({ ...old, [profile.id]: "error" }));
      if (currentPersisted.current.profiles.some((item) => item.id === profile.id)) setNotice(result.error?.message ?? "Could not reconnect. Check the address, token, and browser origin settings.");
      return { ok: false, probe: result, error: result.error?.message ?? "MAPI health, authentication, or protocol compatibility check failed." };
    }
    runtimeById.get(profile.id)?.dispose();
    runtimeById.set(profile.id, runtime);
    setCapabilityState((old) => ({ ...old, [profile.id]: result.capabilities }));
    setProbeInfo((old) => ({ ...old, [profile.id]: result.info ?? {} }));
    setConnectionStates((old) => ({ ...old, [profile.id]: "connected" }));
    const savedProfile = { ...profile, context: result.capabilities.mode };
    setPersisted((old) => {
      const exists = old.profiles.some((item) => item.id === profile.id);
      return { ...old, saveTokens: profile.rememberToken ? true : old.saveTokens, profiles: exists ? old.profiles.map((item) => item.id === profile.id ? savedProfile : item) : [...old.profiles, savedProfile], selectedId: selectOnSuccess ? profile.id : old.selectedId ?? profile.id };
    });
    if (selectOnSuccess) setDialogOpen(false);
    setNotice("");
    return { ok: true, probe: result };
  }, []);

  useEffect(() => {
    if (!ready) return;
    const profiles = persisted.profiles.filter((profile) => profile.token && !runtimeById.has(profile.id) && !autoProbeStarted.current.has(profile.id));
    for (const profile of profiles) autoProbeStarted.current.add(profile.id);
    void (async () => {
      for (const profile of profiles) {
        await connectProfile(profile, false, false, () => currentPersisted.current.profiles.some((item) => item.id === profile.id));
      }
    })();
  }, [connectProfile, persisted.profiles, ready]);

  const reconnect = async (profile: ConnectionProfile) => {
    if (!profile.token) { setDialogProfile(profile); setDialogOpen(true); return; }
    await connectProfile(profile);
  };

  const refreshProfileCapabilities = async (profileId: string, runtime = runtimeById.get(profileId)) => {
    if (!runtime) return;
    setRefreshingCapabilities(true);
    const result = await runtime.probe();
    if (runtimeById.get(profileId) === runtime && currentPersisted.current.profiles.some((profile) => profile.id === profileId)) {
      if (result.ok && result.authenticated && result.compatible) {
        setCapabilityState((old) => ({ ...old, [profileId]: result.capabilities }));
        setProbeInfo((old) => ({ ...old, [profileId]: result.info ?? {} }));
        setConnectionStates((old) => ({ ...old, [profileId]: "connected" }));
        setNotice("");
      } else setNotice(result.error?.message ?? "Could not refresh MAPI capabilities.");
    }
    setRefreshingCapabilities(false);
  };

  const selectTab = (profile: ConnectionProfile) => {
    if (profile.id !== currentPersisted.current.selectedId) {
      attempt.current++;
      pendingDialogController.current?.abort(); pendingDialogController.current = null;
      pendingDialogRuntime.current?.dispose(); pendingDialogRuntime.current = null;
      setAction(null);
    }
    setPersisted((old) => ({ ...old, selectedId: profile.id }));
    const state = connectionStates[profile.id];
    if (state !== "connected") void reconnect(profile);
  };

  const requestRemoval = (id: string) => {
    setRemoveTargetId(id);
  };
  const confirmRemoval = () => {
    const id = removeTargetId;
    if (!id) return;
    if (id === currentPersisted.current.selectedId) setAction(null);
    clearActionDrafts(id);
    if (pendingDialogRuntime.current?.profile.id === id) { pendingDialogController.current?.abort(); pendingDialogController.current = null; pendingDialogRuntime.current.dispose(); pendingDialogRuntime.current = null; attempt.current++; }
    runtimeById.get(id)?.dispose(); runtimeById.delete(id);
    for (const controller of runControllers.current.get(id) ?? []) controller.abort();
    runControllers.current.delete(id);
    const next = removeProfile(id, currentPersisted.current);
    try { localStorage.removeItem(`mapi.dashboard.presets.${id}`); } catch { /* storage may be blocked */ }
    setPersisted(next);
    setRecords((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setPresetMap((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setEvents((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setConnectionStates((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setCapabilityState((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setProbeInfo((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setStreamEnabled((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    setStreamOptions((old) => { const copy = { ...old }; delete copy[id]; return copy; });
    if (dialogProfile?.id === id) closeDialog();
    setRemoveTargetId(null);
  };

  const abortProfileRequests = (id: string) => {
    for (const controller of runControllers.current.get(id) ?? []) controller.abort();
    runControllers.current.delete(id);
  };

  const downloadProfiles = () => {
    const blob = new Blob([exportProfiles(persisted.profiles)], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url; anchor.download = "mapi-connections.json"; anchor.click();
    URL.revokeObjectURL(url);
  };

  const readProfilesFile = async (file?: File) => {
    if (!file) return;
    try {
      const imported = importProfiles(await file.text()).map((profile) => ({ ...profile, id: crypto.randomUUID(), token: undefined, rememberToken: false }));
      if (!imported.length) throw new Error("The file contains no usable profiles.");
      setPersisted((old) => ({ ...old, profiles: [...old.profiles, ...imported], selectedId: old.selectedId ?? imported[0].id }));
      setNotice(`Imported ${imported.length} profile${imported.length === 1 ? "" : "s"}. Re-enter each bearer token before connecting.`);
    } catch (cause) { setNotice(cause instanceof Error ? cause.message : "Could not import profiles."); }
    if (importFileRef.current) importFileRef.current.value = "";
  };

  const copyLatestResponse = async () => {
    if (!latestRecord) return;
    try {
      const response = latestRecord.status === 202 && latestRecord.error
        ? { status: latestRecord.status, headers: latestRecord.responseHeaders, accepted: latestRecord.response, followUpError: latestRecord.error }
        : latestRecord.error ? latestRecord.error : { status: latestRecord.status, headers: latestRecord.responseHeaders, body: latestRecord.response };
      await navigator.clipboard.writeText(JSON.stringify(redactValue(response), null, 2));
      setCopyNotice("Response copied.");
    } catch { setCopyNotice("Clipboard access is unavailable."); }
    setTimeout(() => setCopyNotice(""), 2200);
  };

  const profileTransferControls = <>
    <button className="button small" type="button" onClick={downloadProfiles} disabled={persisted.profiles.length === 0}>Export profiles</button>
    <button className="button small" type="button" onClick={() => importFileRef.current?.click()}>Import profiles</button>
    <input ref={importFileRef} type="file" accept="application/json,.json" hidden aria-label="Choose profile export file" onChange={(event) => void readProfilesFile(event.target.files?.[0])} />
  </>;

  const runAction = async (operation: (typeof operations)[number], values: Record<string, unknown>): Promise<RunRecord | undefined> => {
    if (!activeProfile || !activeRuntime) return undefined;
    const profileId = activeProfile.id;
    if (operation.id === "streamEvents") {
      setStreamOptions((old) => ({ ...old, [profileId]: values }));
      setStreamEnabled((old) => ({ ...old, [profileId]: true }));
      const record: RunRecord = { id: crypto.randomUUID(), operationId: operation.id, values, saved: false, at: new Date().toISOString(), request: { method: "GET", path: operation.path, query: values }, response: { streamSubscription: "started" } };
      if (currentPersisted.current.profiles.some((profile) => profile.id === profileId)) {
        setRecords((old) => {
          const current = [...(old[profileId] ?? []), record];
          const next = { ...old, [profileId]: [...current.filter((item) => item.saved), ...current.filter((item) => !item.saved).slice(-150)] };
          saveSessionHistory(profileId, next[profileId]);
          return next;
        });
        pushEvent(profileId, { type: "Event stream started" });
      }
      return record;
    }
    const controller = new AbortController();
    const inFlight = runControllers.current.get(profileId) ?? new Set<AbortController>();
    inFlight.add(controller); runControllers.current.set(profileId, inFlight);
    let result: RunRecord;
    try { result = await activeRuntime.execute(operation, values, { signal: controller.signal, confirmDestructive: operation.security.destructive && values.confirm === true }); }
    finally { inFlight.delete(controller); }
    if (currentPersisted.current.profiles.some((profile) => profile.id === profileId)) {
      setRecords((old) => {
        const current = [...(old[profileId] ?? []), result];
        const next = { ...old, [profileId]: [...current.filter((item) => item.saved), ...current.filter((item) => !item.saved).slice(-150)] };
        saveSessionHistory(profileId, next[profileId]);
        return next;
      });
      pushEvent(profileId, { type: result.error ? `${operation.label} failed` : `${operation.label} completed`, atEpochMs: Date.now(), data: { status: result.status } });
      if (!result.error && (operation.path.startsWith("/api/v1/server/world") || operation.path.includes("/worlds/") || operation.path.includes("/client/connect"))) {
        void refreshProfileCapabilities(profileId, activeRuntime);
      }
    }
    return result;
  };

  const savePresetForAction = (operation: (typeof operations)[number], values: Record<string, unknown>) => {
    if (!activeProfile) return;
    const safeValues = redactValue(omitTransient(values), new WeakSet(), activeProfile.token) as Record<string, unknown>;
    const preset: Preset = { id: crypto.randomUUID(), operationId: operation.id, values: safeValues, savedAt: new Date().toISOString(), label: operation.label };
    const nextPresets = [...(presetMap[activeProfile.id] ?? []), preset];
    savePresets(activeProfile.id, nextPresets);
    setPresetMap((old) => ({ ...old, [activeProfile.id]: nextPresets }));
    const preview = requestPreview(operation, safeValues);
    const request: RunRecord["request"] = { method: operation.method, path: preview.path as string, ...(preview.query ? { query: preview.query as Record<string, unknown> } : {}), ...(preview.body ? { body: preview.body } : {}) };
    setRecords((old) => ({ ...old, [activeProfile.id]: [...(old[activeProfile.id] ?? []), { id: preset.id, operationId: operation.id, values: safeValues, saved: true, at: preset.savedAt, request: redactValue(request, new WeakSet(), activeProfile.token) as RunRecord["request"] }] }));
  };

  const toggleSaved = (recordId: string) => {
    if (!activeProfile) return;
    const record = (records[activeProfile.id] ?? []).find((item) => item.id === recordId);
    if (!record) return;
    const currentlySaved = record.saved;
    let nextPresets = presetMap[activeProfile.id] ?? [];
    if (currentlySaved) nextPresets = nextPresets.filter((preset) => preset.id !== recordId);
    else nextPresets = [...nextPresets, { id: record.id, operationId: record.operationId, values: record.values, savedAt: record.at, label: operationById.get(record.operationId)?.label, run: record } as Preset];
    savePresets(activeProfile.id, nextPresets);
    setPresetMap((old) => ({ ...old, [activeProfile.id]: nextPresets }));
    setRecords((old) => {
      const updated = (old[activeProfile.id] ?? []).map((item) => item.id === recordId ? { ...item, saved: !currentlySaved } : item);
      const nextRecords = updated.filter((item) => !(currentlySaved && item.id === recordId && item.response === undefined && item.error === undefined && item.status === undefined && item.durationMs === undefined));
      saveSessionHistory(activeProfile.id, nextRecords);
      return { ...old, [activeProfile.id]: nextRecords };
    });
  };

  const clearRecent = (operationId: string) => {
    if (!activeProfile) return;
    setRecords((old) => {
      const next = { ...old, [activeProfile.id]: (old[activeProfile.id] ?? []).filter((item) => item.operationId !== operationId || item.saved) };
      saveSessionHistory(activeProfile.id, next[activeProfile.id]);
      return next;
    });
  };
  const clearSaved = (operationId: string) => {
    if (!activeProfile) return;
    const nextPresets = (presetMap[activeProfile.id] ?? []).filter((preset) => preset.operationId !== operationId);
    savePresets(activeProfile.id, nextPresets);
    setPresetMap((old) => ({ ...old, [activeProfile.id]: nextPresets }));
    setRecords((old) => {
      const nextRecords = (old[activeProfile.id] ?? []).filter((item) => item.operationId !== operationId || !item.saved || item.response !== undefined || item.error !== undefined || item.status !== undefined).map((item) => item.operationId === operationId ? { ...item, saved: false } : item);
      saveSessionHistory(activeProfile.id, nextRecords);
      return { ...old, [activeProfile.id]: nextRecords };
    });
  };

  const toggleAvailable = () => setAvailableOnly((current) => !current);
  const visibleOperations = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    return operations.filter((operation) => {
      const searchable = `${operation.label} ${operation.id} ${operation.path} ${operation.group}`.toLocaleLowerCase();
      if (query && !searchable.includes(query)) return false;
      if (availableOnly && activeState === "connected") return operationAvailability(operation, capabilities).available;
      return true;
    });
  }, [activeState, availableOnly, capabilities, search]);

  const modeMismatch = (operation: (typeof operations)[number]) => operationAvailability(operation, capabilities).reason;

  if (!ready) return <main className="main" aria-busy="true">Loading local connections…</main>;

  return <>
    <header className="app-header">
      <div className="brand"><div className="brand-mark" aria-hidden="true">▦</div><strong>MAPI</strong><span>Minecraft control room</span></div>
      <div className="header-note">DIRECT BROWSER CONNECTION · NO DASHBOARD BACKEND</div>
    </header>
    <nav className="tabs" aria-label="Connections" data-testid="connection-tabs">
      {persisted.profiles.map((profile) => {
        const selected = profile.id === persisted.selectedId;
        const state = connectionStates[profile.id] ?? "disconnected";
        return <div className={`tab-wrap${selected ? " active" : ""}`} key={profile.id} data-testid="connection-tab">
          <button type="button" className="tab-select" aria-current={selected ? "page" : undefined} onClick={() => selectTab(profile)} data-testid={`select-${profile.id}`}><span className={`status-dot${state === "connected" ? "" : " off"}`} aria-label={state} />{profile.name}</button>
          <button className="tab-remove" type="button" aria-label={`Remove ${profile.name}`} data-testid={`remove-${profile.id}`} onClick={() => requestRemoval(profile.id)}>×</button>
        </div>;
      })}
      <button type="button" className="tab-plus" aria-label="Add connection" data-testid="add-connection" onClick={openNew}>+</button>
    </nav>
    <main className="main" data-testid="dashboard-ready">
      {(!activeProfile || activeState !== "connected" || !activeRuntime) ? <>
        <ConnectionDialog inline initialProfile={activeProfile} onTest={(profile) => probeProfile(profile, false)} onConnect={connectProfile} onRemove={activeProfile ? () => requestRemoval(activeProfile.id) : undefined} />
        {notice && <div role="status" className="notice" style={{ maxWidth: 900, margin: "20px auto" }}>{notice}</div>}
        <div className="workspace-foot"><span>{notice || (activeProfile ? `Saved profile · ${activeProfile.baseUrl}` : "No connections yet. Connect a local MAPI instance to start.")}</span><div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>{profileTransferControls}<button className="button small" type="button" onClick={() => { clearSavedCredentials(); setPersisted((old) => ({ ...old, saveTokens: false, profiles: old.profiles.map((profile) => ({ ...profile, token: undefined, rememberToken: false })) })); }}>Clear saved credentials</button></div></div>
      </> : <>
        <section className="statusbar" aria-label="Connected workspace status">
          <div><div className="eyebrow">Connected workspace</div><h1>{activeProfile.name}</h1><p>{prettyHost(activeProfile.baseUrl)} · {String(probeInfo[activeProfile.id]?.loader ?? probeInfo[activeProfile.id]?.platform ?? "MAPI instance")} · Minecraft {String(probeInfo[activeProfile.id]?.minecraftVersion ?? "26.2")} · protocol v1</p></div>
          <div className="badges">
            <span className="badge ok">● Connected · verified</span><span className="badge">{capabilities.mode.toUpperCase()}</span><span className="badge">{capabilities.world === "available" ? "World active" : capabilities.world === "unavailable" ? "No active world" : "World unknown"}</span>
            <button className="button small" type="button" data-testid="refresh-capabilities" onClick={() => void refreshProfileCapabilities(activeProfile.id, activeRuntime)} disabled={refreshingCapabilities}>{refreshingCapabilities ? "Refreshing…" : "Refresh capabilities"}</button>
            <button className="button small" type="button" data-testid="edit-connection" onClick={openEdit}>Edit connection</button>
            <button className="button small" type="button" data-testid="duplicate-connection" onClick={openDuplicate}>Duplicate</button>
            <button className="button small" type="button" data-testid="disconnect-connection" onClick={() => { setAction(null); abortProfileRequests(activeProfile.id); activeRuntime.disconnect(); runtimeById.delete(activeProfile.id); activeRuntime.dispose(); setConnectionStates((old) => ({ ...old, [activeProfile.id]: "disconnected" })); setStreamEnabled((old) => ({ ...old, [activeProfile.id]: false })); }}>Disconnect</button>
            <button className="button small danger" type="button" data-testid="remove-active-connection" onClick={() => requestRemoval(activeProfile.id)}>Remove connection</button>
          </div>
        </section>
        <section className="toolbar" aria-label="Operation filters">
          <input className="text-input" type="search" aria-label="Search actions" placeholder="Search actions, paths, or operation IDs…" value={search} onChange={(event) => setSearch(event.target.value)} data-testid="operation-search" />
          <button className={`button small${!availableOnly ? " primary" : ""}`} type="button" onClick={() => setAvailableOnly(false)} aria-pressed={!availableOnly}>All actions</button>
          <button className={`button small${availableOnly ? " primary" : ""}`} type="button" onClick={toggleAvailable} aria-pressed={availableOnly}>Available only</button>
          <span className="count" data-testid="operation-count">{visibleOperations.length} of {operations.length} operations · {groups.length} groups</span>
        </section>
        <section className="dashboard-grid">
          <div className="action-grid" aria-label="MAPI operations">
            {groups.map((group, index) => {
              const inGroup = visibleOperations.filter((operation) => operation.group === group);
              if (!inGroup.length) return null;
              return <article className="action-group" key={group}>
                <div className="group-title"><span className="group-number">{String(index + 1).padStart(2, "0")}</span><h2>{group}</h2></div>
                <div className="op-buttons">{inGroup.map((operation) => {
                  const reason = modeMismatch(operation);
                  const unavailable = Boolean(reason) && activeState === "connected";
                  return <button className={`op-button${operation.security.destructive ? " danger" : ""}`} key={operation.id} type="button" data-testid="operation-card" data-operation-id={operation.id} disabled={unavailable} title={unavailable ? reason : `${operation.method} ${operation.path}`} onClick={() => setAction(operation)}>{operation.label}</button>;
                })}</div>
              </article>;
            })}
          </div>
          <ResponseInspector latest={latestRecord} events={eventsForActive} capabilities={capabilities} onClearEvents={() => activeProfile && setEvents((old) => ({ ...old, [activeProfile.id]: [] }))} streamActive={Boolean(activeProfile && streamEnabled[activeProfile.id])} onToggleEvents={() => activeProfile && setStreamEnabled((old) => ({ ...old, [activeProfile.id]: !old[activeProfile.id] }))} onCopyResponse={copyLatestResponse} />
        </section>
        <div className="workspace-foot"><span>{copyNotice || "Unavailable controls stay visible with an accessible reason. Browser → MAPI · no backend."}</span><div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>{profileTransferControls}<button className="button small" type="button" onClick={() => { clearSavedCredentials(); setPersisted((old) => ({ ...old, saveTokens: false, profiles: old.profiles.map((profile) => ({ ...profile, token: undefined, rememberToken: false })) })); }}>Clear saved credentials</button></div></div>
      </>}
    </main>
    <ConnectionDialog open={dialogOpen} initialProfile={dialogProfile} onClose={closeDialog} onTest={(profile) => probeProfile(profile, false)} onConnect={connectProfile} onRemove={dialogProfile ? () => requestRemoval(dialogProfile.id) : undefined} />
    <RemovalDialog profile={persisted.profiles.find((profile) => profile.id === removeTargetId) ?? null} onCancel={() => setRemoveTargetId(null)} onConfirm={confirmRemoval} />
    {action && activeProfile && <ActionModal operation={action} connectionId={activeProfile.id} records={activeRecords.filter((record) => record.operationId === action.id)} onClose={() => setAction(null)} onRun={(values) => runAction(action, values)} onSavePreset={(values) => savePresetForAction(action, values)} onToggleSaved={toggleSaved} onClearRecent={() => clearRecent(action.id)} onClearSaved={() => clearSaved(action.id)} leaseStatus={action.security.requiresLease ? "Acquired automatically when needed; lease IDs are never editable or saved." : undefined} />}
  </>;
}
