import { useMemo, useState } from "react";
import { clientLabel, exposure, operationKey, type Bundle, type ContractField } from "../api";
import { fmt, ramp, useTooltip } from "../ui";

/**
 * Clients against the request fields they send, for one operation. Selecting a field (or a change) highlights
 * the clients it would break. Clients with the same set of fields sit together, which shows cohorts.
 */
export function Matrix({ bundle }: { bundle: Bundle }) {
  const operations = bundle.contract.operations.filter(
    (o) => o.calls > 0 && o.fields.some((f) => f.part.startsWith("request") && f.clients),
  );
  const changedFirst = useMemo(() => {
    const changed = new Set(bundle.report.changes.filter((c) => c.breaking).map((c) => operationKey(c.operation)));
    return [...operations].sort((a, b) =>
      Number(changed.has(operationKey(b))) - Number(changed.has(operationKey(a))) || b.calls - a.calls);
  }, [bundle, operations]);
  const [opKey, setOpKey] = useState<string>(changedFirst[0] ? operationKey(changedFirst[0]) : "");
  const [field, setField] = useState<string | null>(null);
  const [changeId, setChangeId] = useState<string | null>(null);
  const tip = useTooltip();
  const op = changedFirst.find((o) => operationKey(o) === opKey);

  const view = useMemo(() => {
    if (!op) return null;
    const fields = op.fields.filter((f) => f.part.startsWith("request") && f.clients && Object.keys(f.clients).length > 0);
    const id = (f: ContractField) => `${f.part} ${f.path}`;
    const clients = op.callers.map((c) => c.client);
    const signature = (client: string) => fields.map((f) => (f.clients![client] ? "1" : "0")).join("");
    const sorted = [...clients].sort((a, b) => signature(b).localeCompare(signature(a)) || a.localeCompare(b));
    const max = Math.max(1, ...fields.flatMap((f) => Object.values(f.clients!)));
    return { fields, id, clients: sorted, max, signature };
  }, [op]);

  const changes = bundle.report.changes.filter((c) => c.breaking && op && operationKey(c.operation) === operationKey(op));
  const change = changes.find((c) => c.id === changeId) ?? null;
  const hit = useMemo(() => {
    if (change) return new Set(exposure(change)?.top_clients.map((t) => t.client) ?? []);
    if (field && view) {
      const f = view.fields.find((x) => view.id(x) === field);
      return new Set(Object.keys(f?.clients ?? {}));
    }
    return new Set<string>();
  }, [change, field, view]);

  if (!op || !view) {
    return <p className="muted">No operation has request fields observed per client. This view needs client identity and request bodies or query parameters.</p>;
  }
  const changedFields = new Set(changes.filter((c) => c.location.part.startsWith("request")).map((c) => `${c.location.part} ${c.location.field}`));

  return (
    <section className="card">
      <h2>Clients × request fields</h2>
      <p className="sub">
        Who sends what. Select a field or a change to highlight the clients it would break.
      </p>
      <div className="row" style={{ marginBottom: 10, alignItems: "center" }}>
        <label>
          Operation{" "}
          <select value={opKey} style={{ width: "auto" }}
            onChange={(e) => { setOpKey(e.target.value); setField(null); setChangeId(null); }}>
            {changedFirst.map((o) => (
              <option key={operationKey(o)} value={operationKey(o)}>
                {operationKey(o)} ({fmt(o.calls)} calls, {o.clients} clients)
              </option>
            ))}
          </select>
        </label>
      </div>
      {changes.length > 0 && (
        <div style={{ marginBottom: 10 }}>
          <span className="secondary">Breaking changes on this operation: </span>
          {changes.map((c) => (
            <button key={c.id} className="plain" style={{ margin: "0 6px 6px 0", fontWeight: c.id === changeId ? 700 : 400,
              borderStyle: c.evidence === "potential" ? "dashed" : "solid" }}
              aria-pressed={c.id === changeId}
              onClick={() => { setChangeId(c.id === changeId ? null : c.id); setField(null); }}>
              {(c.location.field ?? c.kind).replace(/^\$\.?/, "")} · {c.kind.replace(/^request_|^response_/, "").replace(/_/g, " ")}
              {c.evidence === "potential" ? " (potential)" : ""}
            </button>
          ))}
        </div>
      )}
      {change && (
        <p className="secondary">
          {change.evidence === "potential"
            ? "Potential: every highlighted client calls the operation; traffic does not show which of them read the field."
            : `Observed: ${hit.size} client${hit.size === 1 ? "" : "s"} would be affected.`}
          {change.evidence_note ? ` ${change.evidence_note}` : ""}
        </p>
      )}
      <div className="legend">
        <span className="key">requests carrying the field:</span>
        {[1, 2, 3, 4, 5, 6].map((s) => <span key={s} className="swatch" style={{ background: `var(--ramp-${s})` }} />)}
        <span className="key">few → many (log scale)</span>
        <span className="key"><span className="swatch" style={{ background: "var(--surface-2)" }} /> never sent</span>
        <span className="key">◆ field changed in the candidate</span>
      </div>
      <div className="scroll-x">
        <table className="matrix">
          <thead>
            <tr>
              <th />
              {view.fields.map((f) => (
                <th key={view.id(f)} className={`field${field === view.id(f) ? " active" : ""}`} scope="col">
                  <div role="button" tabIndex={0}
                    onClick={() => { setField(field === view.id(f) ? null : view.id(f)); setChangeId(null); }}
                    onKeyDown={(e) => { if (e.key === "Enter") { setField(view.id(f)); setChangeId(null); } }}
                    title={`${f.part} ${f.path}${f.state === "undocumented" ? " (undocumented)" : ""}`}>
                    {changedFields.has(view.id(f)) ? "◆ " : ""}
                    {f.part === "request.query" ? "?" : ""}{f.path.replace(/^\$\.?/, "") || "(body)"}
                    {f.state === "undocumented" ? " ⚠" : ""}
                  </div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {view.clients.map((client) => (
              <tr key={client} className={hit.has(client) ? "hit" : undefined}>
                <th className="client" scope="row" title={client}>
                  {hit.has(client) ? "● " : ""}{clientLabel(bundle.names, client)}
                </th>
                {view.fields.map((f) => {
                  const count = f.clients![client] ?? 0;
                  const dim = hit.size > 0 && !hit.has(client);
                  return (
                    <td key={view.id(f)} className="cell"
                      style={{ background: ramp(count, view.max), opacity: dim ? 0.3 : 1 }}
                      onMouseEnter={(e) => tip.show(e, (
                        <>
                          <strong>{clientLabel(bundle.names, client)}</strong>
                          {f.part} {f.path}: {count === 0 ? "never sent" : `${fmt(count)} requests`}
                        </>
                      ))}
                      onMouseLeave={tip.hide} />
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted" style={{ marginTop: 10 }}>
        Response fields are not in this matrix: traffic shows who receives a response, not who reads a field in it.
      </p>
      {tip.element}
    </section>
  );
}
