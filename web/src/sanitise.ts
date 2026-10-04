// The sanitiser, in the browser: a HAR file is reduced to shape-only events before anything is uploaded.
// Mirrors io.github.shrishaanth.axon.traffic.Sanitiser without a spec: paths are redacted heuristically and no
// values are kept at all (the server matches the redacted path against the workspace's spec).

type JsonType = "string" | "integer" | "number" | "boolean" | "null" | "object" | "array";

export interface ShapeField {
  path: string;
  types: JsonType[];
}

const MAX_FIELDS = 500;
const MAX_DEPTH = 10;

function typeOf(value: unknown): JsonType {
  if (value === null || value === undefined) return "null";
  if (Array.isArray(value)) return "array";
  switch (typeof value) {
    case "object":
      return "object";
    case "boolean":
      return "boolean";
    case "number":
      return Number.isFinite(value) && Math.floor(value) === value ? "integer" : "number";
    default:
      return "string";
  }
}

function child(parent: string, name: string): string {
  return /^[A-Za-z0-9_-]+$/.test(name) ? `${parent}.${name}` : `${parent}['${name.replace(/\\/g, "\\\\").replace(/'/g, "\\'")}']`;
}

/** Field paths and types of a JSON value; never its values. */
export function shape(body: unknown): { fields: ShapeField[]; truncated: boolean } {
  const types = new Map<string, Set<JsonType>>();
  let truncated = false;
  const walk = (value: unknown, path: string, depth: number) => {
    let seen = types.get(path);
    if (!seen) {
      if (types.size >= MAX_FIELDS) {
        truncated = true;
        return;
      }
      seen = new Set();
      types.set(path, seen);
    }
    const type = typeOf(value);
    seen.add(type);
    if (depth >= MAX_DEPTH) {
      if ((type === "object" && Object.keys(value as object).length > 0) || (type === "array" && (value as unknown[]).length > 0)) {
        truncated = true;
      }
      return;
    }
    if (type === "object") {
      for (const [key, v] of Object.entries(value as Record<string, unknown>)) walk(v, child(path, key), depth + 1);
    } else if (type === "array") {
      for (const v of value as unknown[]) walk(v, `${path}[]`, depth + 1);
    }
  };
  walk(body, "$", 0);
  return { fields: [...types.entries()].map(([path, set]) => ({ path, types: [...set] })), truncated };
}

/** Replaces value-like path segments (numbers, UUIDs, long hex, long tokens, emails) by {*}. */
export function redactPath(path: string): string {
  const out = path
    .split("/")
    .filter((s) => s.length > 0)
    .map((s) =>
      /^[0-9]+$/.test(s) ||
      /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s) ||
      (/^[0-9a-fA-F]{8,}$/.test(s) && /[0-9]/.test(s)) ||
      s.length >= 20 ||
      s.includes("@") ||
      s.includes("%")
        ? "{*}"
        : s,
    );
  return `/${out.join("/")}`;
}

async function pseudonym(salt: string, value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(salt + value));
  return [...new Uint8Array(digest).slice(0, 8)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function parseJson(text: string | undefined, mime: string | undefined): unknown | undefined {
  if (!text) return undefined;
  const looksJson = (mime ?? "").toLowerCase().includes("json") || /^\s*[[{]/.test(text);
  if (!looksJson) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export interface SanitiseOptions {
  /** Request header that identifies the client, or empty for none. */
  identityHeader: string;
  salt: string;
}

export interface SanitiseResult {
  lines: string[];
  skipped: number;
}

/** Turns the entries of a parsed HAR file into JSONL event lines. */
export async function sanitiseHar(har: unknown, options: SanitiseOptions): Promise<SanitiseResult> {
  const entries = (har as { log?: { entries?: unknown[] } })?.log?.entries;
  if (!Array.isArray(entries)) throw new Error("Not a HAR file: no log.entries array.");
  const lines: string[] = [];
  let skipped = 0;
  for (const raw of entries) {
    const entry = raw as {
      startedDateTime?: string;
      time?: number;
      request?: { method?: string; url?: string; headers?: { name: string; value: string }[]; postData?: { mimeType?: string; text?: string } };
      response?: { status?: number; content?: { mimeType?: string; text?: string; encoding?: string } };
    };
    const method = entry.request?.method;
    const url = entry.request?.url;
    const started = entry.startedDateTime ? Date.parse(entry.startedDateTime) : NaN;
    const status = entry.response?.status;
    if (!method || !url || Number.isNaN(started) || typeof status !== "number" || status < 100 || status > 599) {
      skipped++;
      continue;
    }
    let parsed: URL;
    try {
      parsed = new URL(url, "http://local.invalid");
    } catch {
      skipped++;
      continue;
    }
    const event: Record<string, unknown> = {
      v: 1,
      ts: new Date(started).toISOString(),
      method: method.toUpperCase(),
      path: redactPath(parsed.pathname),
      status,
    };
    if (typeof entry.time === "number" && entry.time >= 0) event.latency_ms = entry.time;
    if (options.identityHeader) {
      const header = entry.request?.headers?.find((h) => h.name.toLowerCase() === options.identityHeader.toLowerCase());
      if (header?.value) event.client = await pseudonym(options.salt, header.value);
    }
    const request: Record<string, unknown> = {};
    const names = [...new Set([...parsed.searchParams.keys()])];
    if (names.length > 0) request.query = names.map((n) => ({ path: child("$", n), types: ["string"] }));
    const requestBody = parseJson(entry.request?.postData?.text, entry.request?.postData?.mimeType);
    if (requestBody !== undefined) request.body = shape(requestBody).fields;
    if (Object.keys(request).length > 0) event.request = request;
    let responseText = entry.response?.content?.text;
    if (responseText && entry.response?.content?.encoding === "base64") {
      try {
        responseText = new TextDecoder().decode(Uint8Array.from(atob(responseText), (c) => c.charCodeAt(0)));
      } catch {
        responseText = undefined;
      }
    }
    const responseBody = parseJson(responseText, entry.response?.content?.mimeType);
    if (responseBody !== undefined) {
      const s = shape(responseBody);
      event.response = s.truncated ? { body: s.fields, truncated: true } : { body: s.fields };
    }
    lines.push(JSON.stringify(event));
  }
  return { lines, skipped };
}
