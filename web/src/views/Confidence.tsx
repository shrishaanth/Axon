import { useState } from "react";
import type { Change } from "../api";
import { fmt } from "../ui";

const W = 360;
const H = 190;
const M = { left: 38, right: 12, top: 22, bottom: 30 };
const X_MIN = 1;
const X_MAX = 6; // log10 of requests

// Three field presence rates, in the validated categorical order (blue, orange, aqua), each labelled directly.
const CURVES = [
  { p: 0.1, label: "in 10% of requests", color: "#2a78d6" },
  { p: 0.01, label: "in 1%", color: "#eb6834" },
  { p: 0.001, label: "in 0.1%", color: "#1baf7a" },
];

const x = (log: number) => M.left + ((log - X_MIN) / (X_MAX - X_MIN)) * (W - M.left - M.right);
const y = (v: number) => M.top + (1 - v) * (H - M.top - M.bottom);
const seen = (p: number, n: number) => 1 - Math.pow(1 - p, n);

/**
 * How much the traffic behind a row can be trusted to have shown everything: the chance that a field present at
 * a given rate has been seen at least once after n requests, with this operation's n marked. The curve is the
 * one E2 measured (docs/evaluation.md); it is arithmetic, not a fitted model.
 */
export function Confidence({ change }: { change: Change }) {
  const [hover, setHover] = useState<number | null>(null);
  const n = change.confidence.events_in_scope;
  const logN = n > 0 ? Math.min(X_MAX, Math.max(X_MIN, Math.log10(n))) : null;
  const at = hover ?? logN;
  const bound = change.confidence.unseen_rate_upper_95;

  return (
    <div>
      <div className="legend" style={{ margin: "0 0 4px" }}>
        <span className="key">a field present</span>
        {CURVES.map((c) => (
          <span key={c.p} className="key"><span className="swatch" style={{ background: c.color, height: 3, width: 18 }} />{c.label}</span>
        ))}
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ maxWidth: 420 }} role="img"
        aria-label={`Chance that a field has been seen at least once, by number of requests. This operation has ${fmt(n)} requests.`}
        onMouseMove={(e) => {
          const rect = e.currentTarget.getBoundingClientRect();
          const px = ((e.clientX - rect.left) / rect.width) * W;
          const log = X_MIN + ((px - M.left) / (W - M.left - M.right)) * (X_MAX - X_MIN);
          setHover(Math.min(X_MAX, Math.max(X_MIN, log)));
        }}
        onMouseLeave={() => setHover(null)}>
        <g className="grid">
          {[0, 0.5, 1].map((v) => (
            <g key={v}>
              <line x1={M.left} x2={W - M.right} y1={y(v)} y2={y(v)} />
              <text x={M.left - 6} y={y(v)} dy="0.35em" textAnchor="end">{v * 100}%</text>
            </g>
          ))}
        </g>
        {[1, 2, 3, 4, 5, 6].map((log) => (
          <text key={log} x={x(log)} y={H - M.bottom + 14} textAnchor="middle">
            {log >= 6 ? "1M" : log >= 3 ? `${Math.pow(10, log - 3)}k` : Math.pow(10, log)}
          </text>
        ))}
        <text x={(M.left + W - M.right) / 2} y={H - 2} textAnchor="middle">requests for the operation</text>
        {CURVES.map((c) => {
          const points: string[] = [];
          for (let log = X_MIN; log <= X_MAX + 1e-9; log += 0.1) {
            points.push(`${x(log).toFixed(1)},${y(seen(c.p, Math.pow(10, log))).toFixed(1)}`);
          }
          return (
            <g key={c.p}>
              <polyline points={points.join(" ")} fill="none" stroke={c.color} strokeWidth={2} />
              {at !== null && (
                <circle cx={x(at)} cy={y(seen(c.p, Math.pow(10, at)))} r={4} fill={c.color}
                  stroke="var(--surface-1)" strokeWidth={2} />
              )}
            </g>
          );
        })}
        {logN !== null && (
          <g>
            <line x1={x(logN)} x2={x(logN)} y1={M.top} y2={H - M.bottom} stroke="var(--text-primary)" strokeDasharray="3 3" />
            <text x={x(logN)} y={M.top - 8} textAnchor={logN > 5 ? "end" : "middle"} className="label-strong">this operation</text>
          </g>
        )}
      </svg>
      <p className="secondary" style={{ margin: "4px 0 0" }}>
        {at !== null && (
          <>
            After {fmt(Math.round(Math.pow(10, at)))} requests, a field present in 10% / 1% / 0.1% of them has been seen with
            probability {CURVES.map((c) => `${(seen(c.p, Math.pow(10, at)) * 100).toFixed(0)}%`).join(" / ")}.{" "}
          </>
        )}
        {n === 0
          ? "No request for this operation was observed, so absence of clients is not evidence of safety."
          : bound !== null && `Anything never seen in these ${fmt(n)} requests occurs in under ${(bound * 100).toPrecision(2)}% of them (95% bound). `}
        Clients calling less than {change.confidence.min_detectable_rate_per_day.toPrecision(2)} times a day may be
        missing from a {change.confidence.window_days.toFixed(0)}-day window.
      </p>
    </div>
  );
}
