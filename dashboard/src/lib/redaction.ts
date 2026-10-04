const SECRET_KEYS =
  /^(authorization|token|leaseid|lease_id|password|secret|access_token|streamticket)$/i;
export function redactValue(
  value: unknown,
  seen = new WeakSet<object>(),
  token?: string,
): unknown {
  if (typeof value === "string")
    return token ? value.split(token).join("[REDACTED]") : value;
  if (Array.isArray(value))
    return value.map((v) => redactValue(v, seen, token));
  if (!value || typeof value !== "object") return value;
  if (seen.has(value)) return "[Circular]";
  seen.add(value);
  const result: Record<string, unknown> = {};
  for (const [key, child] of Object.entries(value))
    result[key] = SECRET_KEYS.test(key)
      ? "[REDACTED]"
      : redactValue(child, seen, token);
  seen.delete(value);
  return result;
}
export function redactText(text: string, token?: string): string {
  let safe = text.replace(/(Bearer\s+)[^\s"']+/gi, "$1[REDACTED]");
  if (token) safe = safe.split(token).join("[REDACTED]");
  return safe;
}
export function redactedCurl(
  baseUrl: string,
  request: {
    method: string;
    path: string;
    query?: Record<string, unknown>;
    body?: unknown;
  },
): string {
  const url = new URL(request.path, baseUrl);
  for (const [k, v] of Object.entries(request.query ?? {}))
    if (v !== undefined)
      url.searchParams.set(k, Array.isArray(v) ? v.join(",") : String(v));
  const args = [
    `curl -X ${request.method}`,
    `'${url.toString()}'`,
    "-H 'Authorization: Bearer [REDACTED]'",
    "-H 'Content-Type: application/json'",
  ];
  if (request.body !== undefined)
    args.push(
      `--data '${JSON.stringify(redactValue(request.body)).replaceAll("'", "'\\''")}'`,
    );
  return args.join(" ");
}
