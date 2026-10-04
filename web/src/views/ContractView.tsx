import { useState } from "react";
import { day, operationKey, type Bundle, type ContractField } from "../api";
import { fmt } from "../ui";

const STATE_TEXT: Record<ContractField["state"], string> = {
  documented_and_used: "documented, used",
  never_seen: "documented, never seen",
  undocumented: "undocumented",
};

/** Per operation: what the spec declares next to what traffic showed. */
export function ContractView({ bundle }: { bundle: Bundle }) {
  const operations = [...bundle.contract.operations].sort((a, b) => b.calls - a.calls);
  const [opKey, setOpKey] = useState(operations[0] ? operationKey(operations[0]) : "");
  const [filter, setFilter] = useState<"all" | ContractField["state"]>("all");
  const op = operations.find((o) => operationKey(o) === opKey);
  const drift = bundle.report.drift;
  const warnings = drift.filter((d) => d.severity === "warning");

  if (!op) return <p className="muted">The spec declares no operations.</p>;
  const groups = new Map<string, ContractField[]>();
  for (const f of op.fields) {
    if (filter !== "all" && f.state !== filter) continue;
    const key = f.part === "response.body" ? `Response ${f.status}` : f.part === "request.query" ? "Query parameters" : "Request body";
    groups.set(key, [...(groups.get(key) ?? []), f]);
  }
  const counts = { documented_and_used: 0, never_seen: 0, undocumented: 0 };
  for (const f of op.fields) counts[f.state]++;

  return (
    <>
      <section className="card">
        <h2>Drift: where the running API differs from its spec</h2>
        <p className="sub">
          {warnings.length} warnings and {drift.length - warnings.length} informational findings over{" "}
          {fmt(bundle.contract.events)} events ({fmt(bundle.contract.matched_events)} matched an operation).
        </p>
        <div className="scroll-x">
          <table>
            <thead><tr><th>Finding</th><th>Operation</th><th>Where</th><th>Detail</th></tr></thead>
            <tbody>
              {warnings.map((d, i) => (
                <tr key={i}>
                  <td>{d.kind.replace(/_/g, " ")}</td>
                  <td className="mono">{d.operation ? operationKey(d.operation) : d.path}</td>
                  <td className="mono">{d.field ?? d.status ?? ""}</td>
                  <td className="secondary">{d.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {drift.length > warnings.length && (
          <details style={{ marginTop: 8 }}>
            <summary>{drift.length - warnings.length} informational (documented but unused, undocumented 5xx)</summary>
            <ul className="limits">
              {drift.filter((d) => d.severity === "info").map((d, i) => (
                <li key={i}><span className="mono">{d.operation ? operationKey(d.operation) : ""} {d.field ?? d.status ?? ""}</span> {d.detail}</li>
              ))}
            </ul>
          </details>
        )}
      </section>

      <section className="card">
        <h2>Declared vs observed</h2>
        <div className="row" style={{ alignItems: "center", marginBottom: 8 }}>
          <label>
            Operation{" "}
            <select value={opKey} onChange={(e) => setOpKey(e.target.value)} style={{ width: "auto" }}>
              {operations.map((o) => (
                <option key={operationKey(o)} value={operationKey(o)}>{operationKey(o)} ({fmt(o.calls)} calls)</option>
              ))}
            </select>
          </label>
          <label>
            Show{" "}
            <select value={filter} onChange={(e) => setFilter(e.target.value as typeof filter)} style={{ width: "auto" }}>
              <option value="all">all fields ({op.fields.length})</option>
              <option value="documented_and_used">documented, used ({counts.documented_and_used})</option>
              <option value="never_seen">documented, never seen ({counts.never_seen})</option>
              <option value="undocumented">undocumented ({counts.undocumented})</option>
            </select>
          </label>
        </div>
        <p className="secondary" style={{ marginTop: 0 }}>
          {fmt(op.calls)} calls from {op.clients} clients{op.last_seen ? `, last seen ${day(op.last_seen)}` : ""}.
          {op.latency_ms && ` Latency p50 ${op.latency_ms.p50} ms, p95 ${op.latency_ms.p95} ms, p99 ${op.latency_ms.p99} ms (bucketed; may overstate by up to 25%).`}
        </p>
        <p style={{ display: "flex", gap: 8, flexWrap: "wrap", margin: "0 0 12px" }}>
          {op.statuses.map((s) => (
            <span key={s.status} className="pill" style={s.documented ? undefined : { borderColor: "var(--undocumented)" }}
              title={s.documented ? "documented" : "returned, but not in the spec"}>
              {s.status}{s.documented ? "" : " undocumented"} · {fmt(s.count)}
            </span>
          ))}
        </p>
        <div className="legend">
          <span className="key state documented_and_used"><span className="mark" /> documented and used</span>
          <span className="key state never_seen"><span className="mark" /> documented, never seen</span>
          <span className="key state undocumented"><span className="mark" /> undocumented</span>
          <span className="key">bar = share of bodies (or calls) carrying the field</span>
        </div>
        {[...groups.entries()].map(([title, fields]) => (
          <div key={title} style={{ marginBottom: 14 }}>
            <h3 style={{ fontSize: 13, margin: "8px 0 4px" }}>{title} <span className="muted">· {fmt(fields[0].of)} observed</span></h3>
            {fields.map((f) => (
              <div className="field-row" key={`${f.part}${f.status}${f.path}`}>
                <span className="mono" title={f.path}>
                  {f.path.replace(/^\$\.?/, "") || "(root)"}
                  {f.required ? <span className="muted"> required</span> : null}
                </span>
                <span className={`state ${f.state}`}><span className="mark" />{STATE_TEXT[f.state].replace("documented, ", "")}</span>
                <span className="bar-track" title={`${fmt(f.count)} of ${fmt(f.of)}`}>
                  <span className={`bar-fill${f.state === "undocumented" ? " undocumented" : ""}`}
                    style={{ width: `${Math.min(100, (f.presence_rate ?? 0) * 100)}%` }} />
                </span>
                <span className="count secondary" style={{ textAlign: "right", fontVariantNumeric: "tabular-nums" }}>
                  {f.of > 0 ? `${((f.presence_rate ?? 0) * 100).toFixed(f.presence_rate && f.presence_rate < 0.1 ? 1 : 0)}%` : "no data"}
                </span>
              </div>
            ))}
          </div>
        ))}
        {groups.size === 0 && <p className="muted">Nothing matches the filter.</p>}
      </section>
    </>
  );
}
