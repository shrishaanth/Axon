// Types of the JSON the UI reads, and the two places it can come from: the bundled demo (static files) or a
// live Axon API. The shapes mirror schemas/impact-report.schema.json and the server's view endpoints.

export type Severity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "DORMANT" | "NONE_OBSERVED" | "UNRATED";
export type Evidence = "observed" | "potential" | "none";

export interface Exposure {
  clients: number | null;
  requests: number;
  share_recent: number | null;
  k_recent: number | null;
  first_seen: string | null;
  last_seen: string | null;
  top_clients: { client: string; requests: number; last_seen: string }[];
}

export interface Change {
  id: string;
  kind: string;
  breaking: boolean;
  approximated?: boolean;
  partially_analysed?: boolean;
  operation: { method: string; path: string };
  location: { part: string; status?: string; field?: string };
  description: string;
  evidence: Evidence;
  observed_affected?: Exposure;
  potentially_affected?: Exposure;
  evidence_note?: string;
  severity: Severity | null;
  rank: number | null;
  spec_only_rank: number | null;
  confidence: {
    events_in_scope: number;
    window_days: number;
    min_detectable_rate_per_day: number;
    unseen_rate_upper_95: number | null;
    tier: string;
  };
}

export interface Drift {
  kind: string;
  severity: "warning" | "info";
  operation: { method: string; path: string } | null;
  path?: string;
  location?: string;
  field?: string;
  status?: string;
  detail: string;
}

export interface Report {
  generated_at: string;
  inputs: {
    baseline: { path: string; title?: string; version?: string };
    candidate: { path: string; title?: string; version?: string };
    usage: { events: number; matched_events: number; unmatched_events: number };
  };
  config: {
    window: { start: string | null; end: string | null; days: number };
    client_identity: { mode: string };
    thresholds: { recent_days: number; stale_days: number };
  };
  summary: { changes_total: number; breaking_total: number; active_clients: number | null };
  changes: Change[];
  drift: Drift[];
  limitations: string[];
}

export interface ContractField {
  part: string;
  status?: string;
  path: string;
  state: "documented_and_used" | "never_seen" | "undocumented";
  required?: boolean;
  declared_types?: string[];
  count: number;
  of: number;
  presence_rate?: number;
  observed_types?: Record<string, number>;
  clients?: Record<string, number>;
}

export interface ContractOperation {
  method: string;
  path: string;
  calls: number;
  clients: number;
  last_seen?: string;
  statuses: { status: string; documented: boolean; count: number }[];
  latency_ms?: { p50: number; p95: number; p99: number; samples: number };
  callers: { client: string; calls: number; last_seen: string }[];
  fields: ContractField[];
}

export interface Contract {
  operations: ContractOperation[];
  unmatched: { request: string; count: number }[];
  events: number;
  matched_events: number;
}

export interface ClientRow {
  client: string;
  events: number;
  first_seen: string;
  last_seen: string;
  days: Record<string, number>;
  operations: Record<string, number>;
}

export interface Clients {
  clients: ClientRow[];
  clients_total: number;
  window_start?: string;
  window_end?: string;
}

export interface Explore {
  title: string | null;
  version: string | null;
  openapi: string;
  stats: { operations: number; component_schemas: number; unsupported_by_construct: Record<string, number> };
  operations: { method: string; path: string; deprecated: boolean; approximated: boolean; partially_analysed: boolean }[];
}

export interface Bundle {
  source: "demo" | "live";
  report: Report;
  contract: Contract;
  clients: Clients;
  explore: Explore;
  /** Friendly names for client pseudonyms; only the demo has them. */
  names: Record<string, string>;
}

export interface LiveSettings {
  api: string;
  workspace: string;
  adminKey: string;
}

async function getJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body.message) message = body.message;
    } catch {
      // keep the status text
    }
    throw new Error(message);
  }
  return response.json() as Promise<T>;
}

export async function loadDemo(): Promise<Bundle> {
  const base = `${import.meta.env.BASE_URL}demo`;
  const [report, contract, clients, explore, names] = await Promise.all([
    getJson<Report>(`${base}/report.json`),
    getJson<Contract>(`${base}/contract.json`),
    getJson<Clients>(`${base}/clients.json`),
    getJson<Explore>(`${base}/explore.json`),
    getJson<Record<string, string>>(`${base}/client-names.json`).catch(() => ({})),
  ]);
  return { source: "demo", report, contract, clients, explore, names };
}

/** Runs the impact analysis of a candidate spec against a live workspace and loads the views beside it. */
export async function loadLive(settings: LiveSettings, candidate: string, candidateName: string): Promise<Bundle> {
  const root = `${settings.api.replace(/\/$/, "")}/api/workspaces/${settings.workspace}`;
  const headers = { "X-Axon-Admin": settings.adminKey };
  const [report, contract, clients, explore] = await Promise.all([
    getJson<Report>(`${root}/impact?name=${encodeURIComponent(candidateName)}`, {
      method: "POST",
      headers: { ...headers, "Content-Type": "text/plain" },
      body: candidate,
    }),
    getJson<Contract>(`${root}/contract`, { headers }),
    getJson<Clients>(`${root}/clients`, { headers }),
    getJson<Explore>(`${root}/explore`, { headers }),
  ]);
  return { source: "live", report, contract, clients, explore, names: {} };
}

export async function createWorkspace(api: string, name: string) {
  return getJson<{ id: string; ingest_key: string; admin_key: string }>(`${api.replace(/\/$/, "")}/api/workspaces`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name }),
  });
}

export async function uploadSpec(settings: LiveSettings, name: string, text: string) {
  const root = `${settings.api.replace(/\/$/, "")}/api/workspaces/${settings.workspace}`;
  return getJson<{ operations: number }>(`${root}/spec?name=${encodeURIComponent(name)}`, {
    method: "PUT",
    headers: { "X-Axon-Admin": settings.adminKey, "Content-Type": "text/plain" },
    body: text,
  });
}

export async function ingest(api: string, ingestKey: string, lines: string[]) {
  let accepted = 0;
  let rejected = 0;
  for (let i = 0; i < lines.length; i += 500) {
    const result = await getJson<{ accepted: number; rejected: number }>(`${api.replace(/\/$/, "")}/api/ingest`, {
      method: "POST",
      headers: { "X-Axon-Key": ingestKey, "Content-Type": "text/plain" },
      body: lines.slice(i, i + 500).join("\n"),
    });
    accepted += result.accepted;
    rejected += result.rejected;
  }
  return { accepted, rejected };
}

// ---- small shared helpers

export function exposure(change: Change): Exposure | undefined {
  return change.observed_affected ?? change.potentially_affected;
}

export function operationKey(op: { method: string; path: string }): string {
  return `${op.method} ${op.path}`;
}

export function clientLabel(names: Record<string, string>, id: string): string {
  return names[id] ?? id.slice(0, 8);
}

export function day(iso: string | null | undefined): string {
  return iso ? iso.slice(0, 10) : "never";
}

export function daysBetween(fromIso: string, toIso: string): number {
  return Math.round((Date.parse(toIso) - Date.parse(fromIso)) / 86_400_000);
}

export const SEVERITY_ORDER: Severity[] = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "DORMANT", "NONE_OBSERVED", "UNRATED"];

export function severityText(s: Severity | null): string {
  if (!s) return "";
  return s === "NONE_OBSERVED" ? "none observed" : s.toLowerCase();
}
