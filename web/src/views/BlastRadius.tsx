import { useMemo, useState } from "react";
import { sankey, sankeyLinkHorizontal, type SankeyGraph, type SankeyLink, type SankeyNode } from "d3-sankey";
import { clientLabel, exposure, operationKey, type Bundle, type Change } from "../api";
import { fmt, truncate, useTooltip } from "../ui";

interface NodeDatum {
  id: string;
  kind: "change" | "operation" | "client";
  label: string;
}

interface LinkDatum {
  change: Change;
  /** set on the operation-to-client leg */
  client?: string;
}

type Node = SankeyNode<NodeDatum, LinkDatum>;
type Link = SankeyLink<NodeDatum, LinkDatum>;

const MAX_CHANGES = 10;
const MAX_CLIENTS = 14;
const WIDTH = 1120;

function shortChange(c: Change): string {
  const field = c.location.field ? c.location.field.replace(/^\$\.?/, "") : "";
  const what = c.kind
    .replace(/^request_|^response_/, "")
    .replace(/_/g, " ")
    .replace("field ", "");
  return field ? `${field}: ${what}` : what;
}

/**
 * Change -> operation -> client. Link width is the number of requests behind it. Solid links are observed;
 * dashed, lighter links are potential (callers of the operation, who may not depend on the changed part).
 */
export function BlastRadius({ bundle, selected, onSelect }: {
  bundle: Bundle;
  selected: string | null;
  onSelect: (id: string) => void;
}) {
  const [hovered, setHovered] = useState<string | null>(null);
  const tip = useTooltip();

  const graph = useMemo(() => {
    const changes = bundle.report.changes
      .filter((c) => c.breaking && (exposure(c)?.requests ?? 0) > 0 && (exposure(c)?.top_clients.length ?? 0) > 0)
      .sort((a, b) => (a.rank ?? 0) - (b.rank ?? 0))
      .slice(0, MAX_CHANGES);
    if (changes.length === 0) return null;

    // keep the clients that carry the most requests; fold the rest into one node
    const totals = new Map<string, number>();
    for (const c of changes) {
      for (const t of exposure(c)!.top_clients) totals.set(t.client, (totals.get(t.client) ?? 0) + t.requests);
    }
    const kept = new Set([...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, MAX_CLIENTS).map((e) => e[0]));

    const nodes: NodeDatum[] = [];
    const index = new Map<string, number>();
    const node = (id: string, kind: NodeDatum["kind"], label: string) => {
      if (!index.has(id)) {
        index.set(id, nodes.length);
        nodes.push({ id, kind, label });
      }
      return index.get(id)!;
    };
    const links: Array<{ source: number; target: number; value: number } & LinkDatum> = [];
    for (const c of changes) {
      const from = node(`change:${c.id}`, "change", shortChange(c));
      const op = node(`op:${operationKey(c.operation)}`, "operation", operationKey(c.operation));
      const perClient = new Map<string, number>();
      for (const t of exposure(c)!.top_clients) {
        const key = kept.has(t.client) ? t.client : "other";
        perClient.set(key, (perClient.get(key) ?? 0) + t.requests);
      }
      const total = [...perClient.values()].reduce((a, b) => a + b, 0);
      links.push({ source: from, target: op, value: total, change: c });
      for (const [client, requests] of perClient) {
        const to = node(`client:${client}`, "client", client === "other" ? "other clients" : clientLabel(bundle.names, client));
        links.push({ source: op, target: to, value: requests, change: c, client });
      }
    }
    const height = Math.max(280, Math.max(changes.length, kept.size + 1) * 30);
    const layout = sankey<NodeDatum, LinkDatum>()
      .nodeWidth(10)
      .nodePadding(12)
      .extent([[230, 8], [WIDTH - 150, height - 8]]);
    const out = layout({ nodes: nodes.map((n) => ({ ...n })), links: links.map((l) => ({ ...l })) }) as SankeyGraph<NodeDatum, LinkDatum>;
    return { ...out, height };
  }, [bundle]);

  if (!graph) {
    return <p className="muted">No breaking change has observed traffic behind it, so there is no blast radius to draw.</p>;
  }
  const path = sankeyLinkHorizontal<NodeDatum, LinkDatum>();
  const active = hovered ?? selected;

  return (
    <div>
      <div className="legend" aria-label="Legend">
        <span className="key"><span className="swatch line" /> observed: clients that sent the field or called the operation</span>
        <span className="key"><span className="swatch line dashed" /> potential: callers who may or may not depend on it</span>
        <span className="key">link width = requests</span>
      </div>
      <div className="scroll-x">
        <svg viewBox={`0 0 ${WIDTH} ${graph.height}`} width="100%" style={{ minWidth: 760 }} role="img"
          aria-label="Blast radius: changes on the left flow through operations to the clients they affect. The ranked table below holds the same data.">
          <g fill="none">
            {(graph.links as Link[]).map((l, i) => {
              const potential = l.change.evidence === "potential";
              // only hovering dims the rest; a selection is shown by emphasis, so the whole flow stays readable
              const dim = hovered !== null && hovered !== l.change.id;
              const emphasised = active === l.change.id;
              return (
                <path
                  key={i}
                  d={path(l) ?? undefined}
                  stroke={potential ? "var(--potential)" : "var(--observed)"}
                  strokeWidth={Math.max(1.5, l.width ?? 1)}
                  strokeDasharray={potential ? "7 5" : undefined}
                  strokeOpacity={dim ? 0.12 : emphasised ? 0.95 : potential ? 0.6 : 0.5}
                  style={{ cursor: "pointer" }}
                  onMouseEnter={(e) => {
                    setHovered(l.change.id);
                    tip.show(e, (
                      <>
                        <strong>{l.change.description}</strong>
                        {l.client
                          ? `${l.client === "other" ? "other clients" : clientLabel(bundle.names, l.client)}: ${fmt(l.value)} requests`
                          : `${fmt(l.value)} requests on ${operationKey(l.change.operation)}`}
                        <br />
                        {potential ? "potential: these clients call the operation" : "observed"}
                      </>
                    ));
                  }}
                  onMouseLeave={() => { setHovered(null); tip.hide(); }}
                  onClick={() => onSelect(l.change.id)}
                />
              );
            })}
          </g>
          {(graph.nodes as Node[]).map((n) => {
            const left = n.kind === "change";
            const right = n.kind === "client";
            const changeId = left ? n.id.slice("change:".length) : null;
            const strong = changeId !== null && changeId === active;
            return (
              <g key={n.id} style={{ cursor: left ? "pointer" : "default" }}
                onClick={() => changeId && onSelect(changeId)}
                onMouseEnter={() => changeId && setHovered(changeId)}
                onMouseLeave={() => setHovered(null)}>
                <rect x={n.x0} y={n.y0} width={(n.x1 ?? 0) - (n.x0 ?? 0)} height={Math.max(2, (n.y1 ?? 0) - (n.y0 ?? 0))}
                  rx={2} fill="var(--text-secondary)" />
                <text
                  x={left ? (n.x0 ?? 0) - 8 : right ? (n.x1 ?? 0) + 8 : ((n.x0 ?? 0) + (n.x1 ?? 0)) / 2}
                  y={n.kind === "operation" ? (n.y0 ?? 0) - 5 : ((n.y0 ?? 0) + (n.y1 ?? 0)) / 2}
                  dy={n.kind === "operation" ? 0 : "0.35em"}
                  textAnchor={left ? "end" : right ? "start" : "middle"}
                  className={strong ? "label-strong" : undefined}
                  fontWeight={strong ? 700 : 400}>
                  <title>{n.label}</title>
                  {truncate(n.label, left ? 34 : 30)}
                </text>
              </g>
            );
          })}
        </svg>
      </div>
      {tip.element}
    </div>
  );
}
