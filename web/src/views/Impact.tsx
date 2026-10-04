import { useMemo, useState } from "react";
import { clientLabel, day, exposure, type Bundle, type Change } from "../api";
import { EvidencePill, SeverityPill, Stat, fmt } from "../ui";
import { BlastRadius } from "./BlastRadius";
import { Confidence } from "./Confidence";

export function Impact({ bundle }: { bundle: Bundle }) {
  const { report } = bundle;
  const breaking = useMemo(
    () => report.changes.filter((c) => c.breaking).sort((a, b) => (a.rank ?? 0) - (b.rank ?? 0)),
    [report],
  );
  const safe = report.changes.filter((c) => !c.breaking);
  const [selected, setSelected] = useState<string | null>(breaking[0]?.id ?? null);
  const current = breaking.find((c) => c.id === selected) ?? null;
  const observed = breaking.filter((c) => c.evidence === "observed");
  const affecting = observed.filter((c) => (exposure(c)?.clients ?? 0) > 0);
  const noIdentity = report.config.client_identity.mode === "none";

  return (
    <>
      {noIdentity && (
        <div className="banner">
          <strong>No client identity configured.</strong> Clients cannot be counted, so impact is request volume only
          and severity is unrated.
        </div>
      )}
      <div className="stats">
        <Stat value={breaking.length} label={`breaking changes of ${report.changes.length}`} />
        <Stat value={affecting.length} label="with clients observed to be affected" />
        <Stat value={breaking.length - observed.length} label="potential only (response side)" />
        <Stat value={report.summary.active_clients ?? "n/a"}
          label={`clients active in the last ${report.config.thresholds.recent_days} days`} />
        <Stat value={fmt(report.inputs.usage.matched_events)}
          label={`matched events of ${fmt(report.inputs.usage.events)}`} />
      </div>

      <section className="card">
        <h2>Blast radius</h2>
        <p className="sub">
          {report.inputs.baseline.path} → {report.inputs.candidate.path}. Which changes reach which clients, and
          through which operation.
        </p>
        <BlastRadius bundle={bundle} selected={selected} onSelect={setSelected} />
      </section>

      <div className="row">
        <section className="card grow" style={{ flex: "2 1 560px" }}>
          <h2>Breaking changes, ranked</h2>
          <p className="sub">
            Observed rows first, then potential ones. "Spec-only" is the order a diff tool gives without traffic.
          </p>
          <div className="scroll-x">
            <table>
              <thead>
                <tr>
                  <th className="num">#</th>
                  <th>Severity</th>
                  <th>Change</th>
                  <th className="num">Clients</th>
                  <th className="num">Requests</th>
                  <th>Last seen</th>
                  <th className="num">Spec-only</th>
                </tr>
              </thead>
              <tbody>
                {breaking.map((c) => {
                  const e = exposure(c);
                  return (
                    <tr key={c.id} className={`selectable${c.id === selected ? " selected" : ""}`}
                      onClick={() => setSelected(c.id)} tabIndex={0}
                      onKeyDown={(ev) => { if (ev.key === "Enter" || ev.key === " ") setSelected(c.id); }}>
                      <td className="num">{c.rank}</td>
                      <td><SeverityPill severity={c.severity} evidence={c.evidence} /></td>
                      <td>{c.description}</td>
                      <td className="num">
                        {e?.clients === null || e === undefined ? "n/a" : `${c.evidence === "potential" ? "≤ " : ""}${e.clients}`}
                      </td>
                      <td className="num">{fmt(e?.requests ?? 0)}</td>
                      <td className="nowrap">{day(e?.last_seen)}</td>
                      <td className="num">{c.spec_only_rank}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          {safe.length > 0 && (
            <details style={{ marginTop: 10 }}>
              <summary>{safe.length} safe changes</summary>
              <ul className="limits">{safe.map((c) => <li key={c.id}>{c.description}</li>)}</ul>
            </details>
          )}
        </section>

        <section className="card grow">
          <h2>Selected change</h2>
          {current ? <Detail change={current} names={bundle.names} /> : <p className="muted">Select a row.</p>}
        </section>
      </div>

      <section className="card">
        <h2>What this report cannot tell you</h2>
        <ul className="limits">{report.limitations.map((l) => <li key={l}>{l}</li>)}</ul>
      </section>
    </>
  );
}

function Detail({ change, names }: { change: Change; names: Record<string, string> }) {
  const e = exposure(change);
  return (
    <>
      <p style={{ margin: "0 0 8px" }}>{change.description}</p>
      <p style={{ margin: "0 0 8px", display: "flex", gap: 6, flexWrap: "wrap" }}>
        <SeverityPill severity={change.severity} evidence={change.evidence} />
        <EvidencePill evidence={change.evidence} />
        {change.approximated && <span className="pill" title="The operation uses oneOf/anyOf, which Axon models loosely.">approximated</span>}
      </p>
      {change.evidence === "potential" && (
        <div className="banner">
          <strong>Potential, not observed.</strong> These clients call the operation. Traffic does not show which of
          them read the changed field; in Axon's evaluation about one in four did.
        </div>
      )}
      {change.evidence_note && <p className="secondary">{change.evidence_note}</p>}
      {e && e.top_clients.length > 0 ? (
        <table>
          <thead><tr><th>Client</th><th className="num">Requests</th><th>Last seen</th></tr></thead>
          <tbody>
            {e.top_clients.map((t) => (
              <tr key={t.client}>
                <td title={t.client}>{clientLabel(names, t.client)}</td>
                <td className="num">{fmt(t.requests)}</td>
                <td className="nowrap">{day(t.last_seen)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="muted">No client was observed. That is absence of evidence; see the bound below.</p>
      )}
      {e && e.clients !== null && e.clients > e.top_clients.length && (
        <p className="muted">and {e.clients - e.top_clients.length} more.</p>
      )}
      <h2 style={{ marginTop: 14 }}>How much traffic is behind this</h2>
      <Confidence change={change} />
    </>
  );
}
