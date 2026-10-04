import { useCallback, useState, type ReactNode } from "react";
import { severityText, type Evidence, type Severity } from "./api";

/** Severity is never colour alone: the dot sits next to the word. */
export function SeverityPill({ severity, evidence }: { severity: Severity | null; evidence: Evidence }) {
  if (!severity) return <span className="muted">safe</span>;
  return (
    <span className={`pill sev-${severity}`}>
      <span className="dot" aria-hidden="true" />
      {evidence === "potential" ? "potential " : ""}
      {severityText(severity)}
    </span>
  );
}

export function EvidencePill({ evidence }: { evidence: Evidence }) {
  if (evidence === "none") return null;
  return (
    <span className={`pill ${evidence}`} title={evidence === "observed"
      ? "Traffic shows these clients sent the field or called the operation."
      : "Traffic shows who calls the operation, not who reads the changed field. An upper bound."}>
      {evidence}
    </span>
  );
}

export interface Tip {
  x: number;
  y: number;
  content: ReactNode;
}

/** A tooltip that follows the pointer. Returns the element to render and the handlers to attach. */
export function useTooltip() {
  const [tip, setTip] = useState<Tip | null>(null);
  const show = useCallback((event: { clientX: number; clientY: number }, content: ReactNode) => {
    setTip({ x: event.clientX, y: event.clientY, content });
  }, []);
  const hide = useCallback(() => setTip(null), []);
  const element = tip ? (
    <div
      className="tooltip"
      role="status"
      style={{
        left: Math.min(tip.x + 14, window.innerWidth - 350),
        top: Math.min(tip.y + 14, window.innerHeight - 120),
      }}
    >
      {tip.content}
    </div>
  ) : null;
  return { show, hide, element };
}

export function Stat({ value, label }: { value: ReactNode; label: string }) {
  return (
    <div className="stat">
      <div className="value">{value}</div>
      <div className="label">{label}</div>
    </div>
  );
}

export function fmt(n: number): string {
  return n.toLocaleString("en-US");
}

/** Blue ramp step for a count, on a log scale so that 20 and 2,000 are both visible. */
export function ramp(count: number, max: number): string {
  if (count <= 0) return "var(--surface-2)";
  const t = max <= 1 ? 1 : Math.log(count + 1) / Math.log(max + 1);
  const step = Math.min(6, Math.max(1, Math.ceil(t * 6)));
  return `var(--ramp-${step})`;
}

export function truncate(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}
