import { useMemo } from "react";
import { clientLabel, day, daysBetween, type Bundle } from "../api";
import { fmt, ramp, useTooltip } from "../ui";

const CELL = 11;
const GAP = 2;
const LABEL = 170;
const ROW = 20;

/**
 * One row per client, one cell per day. Percentages hide a client that calls twice a month; a row of mostly
 * empty cells does not.
 */
export function Timeline({ bundle }: { bundle: Bundle }) {
  const tip = useTooltip();
  const data = useMemo(() => {
    const clients = [...bundle.clients.clients];
    const allDays = new Set<string>();
    for (const c of clients) Object.keys(c.days).forEach((d) => allDays.add(d));
    if (allDays.size === 0) return null;
    const sorted = [...allDays].sort();
    const days: string[] = [];
    for (let t = Date.parse(sorted[0]); t <= Date.parse(sorted[sorted.length - 1]); t += 86_400_000) {
      days.push(new Date(t).toISOString().slice(0, 10));
    }
    const end = bundle.clients.window_end ?? `${days[days.length - 1]}T23:59:59Z`;
    clients.sort((a, b) => a.last_seen.localeCompare(b.last_seen));
    const max = Math.max(1, ...clients.flatMap((c) => Object.values(c.days)));
    return { clients, days, end, max };
  }, [bundle]);

  if (!data) {
    return <p className="muted">No client identity in the traffic, so there is no per-client timeline.</p>;
  }
  const { clients, days, end, max } = data;
  const recent = bundle.report.config.thresholds.recent_days;
  const stale = bundle.report.config.thresholds.stale_days;
  const width = LABEL + days.length * (CELL + GAP) + 150;
  const height = 26 + clients.length * ROW;

  return (
    <section className="card">
      <h2>Last seen, per client</h2>
      <p className="sub">
        Quietest clients first. A client seen within {recent} days counts as recent; beyond {stale} days as dormant.
        {bundle.clients.clients_total > clients.length ? ` Showing ${clients.length} of ${bundle.clients.clients_total}.` : ""}
      </p>
      <div className="legend">
        <span className="key">calls per day:</span>
        {[1, 2, 3, 4, 5, 6].map((s) => <span key={s} className="swatch" style={{ background: `var(--ramp-${s})` }} />)}
        <span className="key">few → many (log scale)</span>
        <span className="key"><span className="swatch" style={{ background: "var(--surface-2)" }} /> no calls</span>
      </div>
      <div className="scroll-x">
        <svg viewBox={`0 0 ${width} ${height}`} width={width} role="img"
          aria-label="Calls per client per day. The table of clients with first and last seen dates follows.">
          {days.map((d, i) => (d.endsWith("-01") || i === 0) && (
            <text key={d} x={LABEL + i * (CELL + GAP)} y={12}>{d.slice(0, 7)}</text>
          ))}
          {clients.map((c, row) => {
            const ago = daysBetween(c.last_seen, end);
            const y = 22 + row * ROW;
            return (
              <g key={c.client}>
                <text x={LABEL - 8} y={y + CELL / 2} dy="0.35em" textAnchor="end" className="label-strong">
                  <title>{c.client}</title>
                  {clientLabel(bundle.names, c.client)}
                </text>
                {days.map((d, i) => {
                  const n = c.days[d] ?? 0;
                  return (
                    <rect key={d} x={LABEL + i * (CELL + GAP)} y={y} width={CELL} height={CELL} rx={2}
                      fill={ramp(n, max)}
                      onMouseEnter={(e) => tip.show(e, (
                        <><strong>{clientLabel(bundle.names, c.client)}</strong>{d}: {n === 0 ? "no calls" : `${fmt(n)} calls`}</>
                      ))}
                      onMouseLeave={tip.hide} />
                  );
                })}
                <text x={LABEL + days.length * (CELL + GAP) + 8} y={y + CELL / 2} dy="0.35em">
                  {ago <= 0 ? "seen today" : `${ago} day${ago === 1 ? "" : "s"} ago`}
                  {ago > stale ? " · dormant" : ago > recent ? " · quiet" : ""}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      <details style={{ marginTop: 10 }}>
        <summary>As a table</summary>
        <table>
          <thead><tr><th>Client</th><th className="num">Events</th><th>First seen</th><th>Last seen</th><th className="num">Active days</th></tr></thead>
          <tbody>
            {clients.map((c) => (
              <tr key={c.client}>
                <td title={c.client}>{clientLabel(bundle.names, c.client)}</td>
                <td className="num">{fmt(c.events)}</td>
                <td>{day(c.first_seen)}</td>
                <td>{day(c.last_seen)}</td>
                <td className="num">{Object.keys(c.days).length}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      {tip.element}
    </section>
  );
}
