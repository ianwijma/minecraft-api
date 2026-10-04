import type { CapabilityContext, CapabilityState } from "./types.ts";

export const emptyCapabilities = (): CapabilityContext => ({
  mode: "unknown",
  client: "unknown",
  world: "unknown",
  tickControl: "unknown",
  worldQueries: "unknown",
  commands: "unknown",
  capabilities: [],
  clientCapabilities: [],
});
export function isExpectedCapabilityUnavailable(error: {
  code?: string;
  status?: number;
}): boolean {
  return (
    error.code === "CAPABILITY_UNAVAILABLE" || error.code === "WORLD_NOT_LOADED"
  );
}
export function classifyCapabilities(input: {
  serverStatus?: any;
  worldInfo?: any;
  clientInfo?: any;
  tickState?: any;
}): CapabilityContext {
  const c = emptyCapabilities();
  const world = input.worldInfo,
    client = input.clientInfo;
  c.capabilities = Array.isArray(world?.capabilities)
    ? [...world.capabilities]
    : [];
  c.clientCapabilities = Array.isArray(client?.capabilities)
    ? [...client.capabilities]
    : [];
  c.capabilities = [...new Set([...c.capabilities, ...c.clientCapabilities])];
  c.clientInput = typeof client?.input === "boolean" ? client.input : undefined;
  c.screenshots =
    typeof client?.screenshots === "boolean" ? client.screenshots : undefined;
  c.window = typeof client?.window === "boolean" ? client.window : undefined;
  c.worldSessionId =
    typeof world?.worldSessionId === "string"
      ? world.worldSessionId
      : undefined;
  c.worldPhase = typeof world?.phase === "string" ? world.phase : undefined;
  c.world =
    world?.phase === "ACTIVE"
      ? "available"
      : world?.phase === "NONE" || world?.phase === "UNLOADING"
        ? "unavailable"
        : "unknown";
  const clientPresent =
    !!client &&
    client.bridgeId !== "none" &&
    (client.input === true ||
      client.screenshots === true ||
      client.window === true ||
      c.clientCapabilities.length > 0);
  c.client = clientPresent
    ? "available"
    : client?.bridgeId === "none"
      ? "unavailable"
      : client
        ? "unknown"
        : "unavailable";
  c.tickControl =
    typeof world?.tickControl === "boolean"
      ? world.tickControl
        ? "available"
        : "unavailable"
      : input.tickState?.available === true
        ? "available"
        : input.tickState?.available === false
          ? "unavailable"
          : "unknown";
  c.worldQueries =
    typeof world?.worldQueries === "boolean"
      ? world.worldQueries
        ? "available"
        : "unavailable"
      : c.world;
  c.commands =
    typeof world?.commands === "boolean"
      ? world.commands
        ? "available"
        : "unavailable"
      : c.world;
  if (input.serverStatus?.running === true && clientPresent)
    c.mode = "integrated";
  else if (input.serverStatus?.running === true) c.mode = "dedicated";
  else if (clientPresent)
    c.mode = c.world === "available" ? "remote" : "unknown";
  else c.mode = "unknown";
  return c;
}
export function operationAvailability(
  op: { path: string; security?: { requiredScopes?: string[] } },
  c: CapabilityContext,
): { available: boolean; reason?: string } {
  let state: CapabilityState = "available";
  if (op.path === "/api/v1/client" || op.path.startsWith("/api/v1/client/"))
    state = c.client;
  if (
    op.path.includes("/actions/") ||
    op.path.includes("/inventory") ||
    op.path.includes("/movement/") ||
    op.path.endsWith("/connect")
  )
    state =
      c.clientInput === true
        ? "available"
        : c.clientInput === false
          ? "unavailable"
          : c.client;
  if (op.path.includes("/screenshots") || op.path.includes("tooltip-rendered"))
    state =
      c.screenshots === true ||
      c.clientCapabilities.some((x) => x.toLowerCase().includes("screenshot"))
        ? "available"
        : c.screenshots === false
          ? "unavailable"
          : c.client === "available"
            ? "unknown"
            : c.client;
  if (op.path.includes("/window"))
    state =
      c.window === true ||
      c.clientCapabilities.some((x) => x.toLowerCase().includes("window"))
        ? "available"
        : c.window === false
          ? "unavailable"
          : c.client === "available"
            ? "unknown"
            : c.client;
  if (op.path.startsWith("/api/v1/server/queries/")) state = c.worldQueries;
  if (op.path.startsWith("/api/v1/server/snapshot")) state = c.world;
  if (op.path.startsWith("/api/v1/server/ticks")) state = c.tickControl;
  if (op.path.startsWith("/api/v1/server/commands")) state = c.commands;
  if (op.path.startsWith("/api/v1/server/lan"))
    state =
      c.mode === "integrated"
        ? "available"
        : c.mode === "unknown"
          ? "unknown"
          : "unavailable";
  if (state === "available") return { available: true };
  return {
    available: false,
    reason:
      state === "unknown"
        ? "Capability has not been checked yet."
        : "This MAPI instance does not currently provide this capability.",
  };
}
