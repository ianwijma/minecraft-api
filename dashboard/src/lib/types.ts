import type { Operation } from "./catalog.ts";

export interface ConnectionProfile {
  id: string;
  name: string;
  baseUrl: string;
  token?: string;
  rememberToken: boolean;
  context?: "dedicated" | "menu" | "remote" | "integrated" | "unknown";
}
export interface RequestSnapshot {
  method: string;
  path: string;
  query?: Record<string, unknown>;
  body?: unknown;
}
export interface ApiErrorSnapshot {
  code: string;
  message: string;
  details?: unknown;
  status?: number;
  headers?: Record<string, string>;
}
export interface RunRecord {
  id: string;
  operationId: string;
  values: Record<string, unknown>;
  saved: boolean;
  at: string;
  request: RequestSnapshot;
  response?: unknown;
  status?: number;
  responseHeaders?: Record<string, string>;
  durationMs?: number;
  error?: ApiErrorSnapshot;
}
export type HistoryRecord = RunRecord;
export interface ApiResponse<T = unknown> {
  data: T;
  status: number;
  headers: Record<string, string>;
  durationMs: number;
  request: RequestSnapshot;
}
export interface CapabilityContext {
  mode: "dedicated" | "menu" | "remote" | "integrated" | "unknown";
  client: CapabilityState;
  world: CapabilityState;
  tickControl: CapabilityState;
  worldQueries: CapabilityState;
  commands: CapabilityState;
  capabilities: string[];
  clientCapabilities: string[];
  clientInput?: boolean;
  screenshots?: boolean;
  window?: boolean;
  worldSessionId?: string;
  worldPhase?: string;
}
export type CapabilityState = "available" | "unavailable" | "unknown";
export interface ProbeResult {
  ok: boolean;
  authenticated: boolean;
  compatible: boolean;
  protocolVersion?: number;
  info?: Record<string, unknown>;
  operations?: unknown;
  capabilities: CapabilityContext;
  error?: ApiErrorSnapshot;
}
export interface RuntimeEvent {
  seq?: number;
  type?: string;
  atEpochMs?: number;
  worldSessionId?: string;
  payload?: unknown;
  gap?: boolean;
  data?: unknown;
}
export interface RunOptions {
  signal?: AbortSignal;
  confirmDestructive?: boolean;
  timeoutMs?: number;
}
export type OperationLike = Operation;
