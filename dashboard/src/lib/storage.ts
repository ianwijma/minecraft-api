import type { ConnectionProfile, RunRecord } from "./types.ts";
import { redactValue } from "./redaction.ts";

const VERSION = 1;
const PROFILE_KEY = "mapi.dashboard.profiles";
const HISTORY_KEY = "mapi.dashboard.history";
const MAX_HISTORY = 50;
export interface PersistedState {
  version: number;
  profiles: ConnectionProfile[];
  selectedId: string | null;
  saveTokens: boolean;
}
export interface Preset {
  id: string;
  operationId: string;
  values: Record<string, unknown>;
  savedAt: string;
  label?: string;
  run?: RunRecord;
}
export interface PersistedPresets {
  version: number;
  presets: Preset[];
}
export function loadProfiles(storage?: Storage): PersistedState | null {
  try {
    const target = storage ?? globalThis.localStorage;
    const raw = target.getItem(PROFILE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (
      !isRecord(parsed) ||
      parsed.version !== VERSION ||
      !Array.isArray(parsed.profiles)
    )
      return null;
    const saveTokens = parsed.saveTokens !== false;
    const seen = new Set<string>();
    const profiles: ConnectionProfile[] = parsed.profiles
      .filter(
        (p: any) =>
          p &&
          typeof p.id === "string" &&
          p.id.length > 0 &&
          typeof p.name === "string" &&
          p.name.trim().length > 0 &&
          safeBaseUrl(p.baseUrl) !== null &&
          !seen.has(p.id) &&
          seen.add(p.id),
      )
      .map((p: any) => ({
        id: p.id,
        name: p.name,
        baseUrl: safeBaseUrl(p.baseUrl)!,
        token:
          saveTokens && p.rememberToken === true && typeof p.token === "string"
            ? p.token
            : undefined,
        rememberToken: saveTokens && p.rememberToken === true,
        context: [
          "dedicated",
          "menu",
          "remote",
          "integrated",
          "unknown",
        ].includes(p.context)
          ? p.context
          : undefined,
      }));
    return {
      version: VERSION,
      profiles,
      selectedId: profiles.some((p) => p.id === parsed.selectedId)
        ? parsed.selectedId
        : null,
      saveTokens,
    };
  } catch {
    return null;
  }
}
export function saveProfiles(
  state: PersistedState,
  storage?: Storage,
): boolean {
  try {
    const target = storage ?? globalThis.localStorage;
    const saveTokens = state.saveTokens !== false;
    const profiles = state.profiles.map((p) => ({
      id: p.id,
      name: p.name,
      baseUrl: p.baseUrl,
      context: p.context,
      rememberToken: saveTokens && p.rememberToken,
      token: saveTokens && p.rememberToken ? p.token : undefined,
    }));
    target.setItem(
      PROFILE_KEY,
      JSON.stringify({
        version: VERSION,
        profiles,
        selectedId: state.selectedId,
        saveTokens,
      }),
    );
    return true;
  } catch {
    return false;
  }
}
export function clearSavedCredentials(storage?: Storage): boolean {
  try {
    const target = storage ?? globalThis.localStorage;
    const state = loadProfiles(target);
    if (state)
      return saveProfiles(
        {
          ...state,
          saveTokens: true,
          profiles: state.profiles.map((p) => ({
            ...p,
            token: undefined,
            rememberToken: false,
          })),
        },
        target,
      );
    target.removeItem(PROFILE_KEY);
    return true;
  } catch {
    return false;
  }
}
export function removeProfile(
  id: string,
  state: PersistedState,
  storage?: Storage,
  session?: Storage,
): PersistedState {
  const profiles = state.profiles.filter((p) => p.id !== id);
  const next = {
    ...state,
    profiles,
    selectedId:
      state.selectedId === id ? (profiles[0]?.id ?? null) : state.selectedId,
  };
  saveProfiles(next, storage);
  try {
    const target = session ?? globalThis.sessionStorage;
    const histories = loadSessionHistory(target);
    delete histories[id];
    target.setItem(HISTORY_KEY, JSON.stringify(histories));
  } catch {}
  try {
    (storage ?? globalThis.localStorage).removeItem(
      `mapi.dashboard.presets.${id}`,
    );
  } catch {}
  return next;
}
export function loadSessionHistory(
  storage?: Storage,
): Record<string, RunRecord[]> {
  try {
    const target = storage ?? globalThis.sessionStorage;
    const x = JSON.parse(target.getItem(HISTORY_KEY) || "{}");
    if (!isRecord(x)) return {};
    const history: Record<string, RunRecord[]> = {};
    for (const [id, records] of Object.entries(x))
      if (Array.isArray(records))
        history[id] = records.filter(isRunRecord).slice(-MAX_HISTORY);
    return history;
  } catch {
    return {};
  }
}
export function saveSessionHistory(
  connectionId: string,
  records: RunRecord[],
  storage?: Storage,
): boolean {
  try {
    const target = storage ?? globalThis.sessionStorage;
    const all = loadSessionHistory(target);
    all[connectionId] = records.slice(-MAX_HISTORY);
    target.setItem(HISTORY_KEY, JSON.stringify(all));
    return true;
  } catch {
    return false;
  }
}
export function loadPresets(connectionId: string, storage?: Storage): Preset[] {
  try {
    const target = storage ?? globalThis.localStorage;
    const x = JSON.parse(
      target.getItem(`mapi.dashboard.presets.${connectionId}`) || "{}",
    );
    return isRecord(x) && x.version === VERSION && Array.isArray(x.presets)
      ? x.presets.filter(isPreset)
      : [];
  } catch {
    return [];
  }
}
export function savePresets(
  connectionId: string,
  presets: Preset[],
  storage?: Storage,
): boolean {
  try {
    const target = storage ?? globalThis.localStorage;
    target.setItem(
      `mapi.dashboard.presets.${connectionId}`,
      JSON.stringify({
        version: VERSION,
        presets: presets.map((p) => ({
          ...p,
          values: sanitizePreset(omitTransient(p.values)),
          run: p.run ? secureRun(p.run) : undefined,
        })),
      }),
    );
    return true;
  } catch {
    return false;
  }
}
export function omitTransient(value: any): any {
  if (Array.isArray(value)) return value.map(omitTransient);
  if (value && typeof value === "object") {
    const o: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value))
      if (!/^(leaseId|worldSessionId|containerId|screenId|confirm)$/i.test(k))
        o[k] = omitTransient(v);
    return o;
  }
  return value;
}
function sanitizePreset(value: any): any {
  return redactValue(value);
}
function secureRun(run: RunRecord): RunRecord {
  return {
    ...run,
    values: sanitizePreset(omitTransient(run.values)),
    request: omitTransient(redactValue(run.request)) as RunRecord["request"],
    response: undefined,
    error: undefined,
    status: undefined,
    responseHeaders: undefined,
    durationMs: undefined,
  };
}
export function exportProfiles(profiles: ConnectionProfile[]): string {
  return JSON.stringify(
    {
      version: VERSION,
      profiles: profiles.map(({ token, ...p }) => ({
        ...p,
        rememberToken: false,
      })),
    },
    null,
    2,
  );
}
export function importProfiles(
  text: string,
  existingIds: string[] = [],
): ConnectionProfile[] {
  const x = JSON.parse(text);
  if (!isRecord(x) || x.version !== VERSION || !Array.isArray(x.profiles))
    throw new Error("Invalid or unsupported profile export version");
  const used = new Set(existingIds);
  return x.profiles
    .filter(
      (p: any) =>
        p && typeof p.name === "string" && safeBaseUrl(p.baseUrl) !== null,
    )
    .map((p: any) => {
      let id = typeof p.id === "string" ? p.id : crypto.randomUUID();
      if (used.has(id)) id = crypto.randomUUID();
      used.add(id);
      return {
        id,
        name: p.name,
        baseUrl: safeBaseUrl(p.baseUrl)!,
        rememberToken: false,
        context: [
          "dedicated",
          "menu",
          "remote",
          "integrated",
          "unknown",
        ].includes(p.context)
          ? p.context
          : undefined,
      };
    });
}
function safeBaseUrl(value: unknown): string | null {
  if (typeof value !== "string") return null;
  try {
    const url = new URL(value);
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      return null;
    return url.origin;
  } catch {
    return null;
  }
}
function isRecord(value: unknown): value is Record<string, any> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}
function isRunRecord(value: unknown): value is RunRecord {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.operationId !== "string" ||
    !isRecord(value.values) ||
    typeof value.saved !== "boolean" ||
    typeof value.at !== "string" ||
    !isRecord(value.request) ||
    !["GET", "POST"].includes(value.request.method) ||
    typeof value.request.path !== "string"
  )
    return false;
  if (
    value.error !== undefined &&
    (!isRecord(value.error) ||
      typeof value.error.code !== "string" ||
      typeof value.error.message !== "string")
  )
    return false;
  return true;
}
function isPreset(value: unknown): value is Preset {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.operationId === "string" &&
    isRecord(value.values) &&
    typeof value.savedAt === "string" &&
    (value.label === undefined || typeof value.label === "string") &&
    (value.run === undefined || isRunRecord(value.run))
  );
}
