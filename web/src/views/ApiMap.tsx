import { useEffect, useMemo, useRef, useState } from "react";
import cytoscape from "cytoscape";
import { exposure, operationKey, type Bundle } from "../api";
import { fmt } from "../ui";

type State = "breaking_observed" | "breaking_potential" | "changed_safe" | "unchanged";

const STATE_TEXT: Record<State, string> = {
  breaking_observed: "breaking, clients observed",
  breaking_potential: "breaking, potential or no clients",
  changed_safe: "changed, safe",
  unchanged: "unchanged",
};

function css(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Operations laid out as the path tree, sized by traffic, coloured by what the candidate does to them. */
export function ApiMap({ bundle }: { bundle: Bundle }) {
  const container = useRef<HTMLDivElement>(null);
  const [picked, setPicked] = useState<string | null>(null);

  const operations = useMemo(() => {
    const byOp = new Map<string, State>();
    for (const c of bundle.report.changes) {
      const key = operationKey(c.operation);
      const current = byOp.get(key);
      let state: State = "changed_safe";
      if (c.breaking) {
        state = c.evidence === "observed" && (exposure(c)?.clients ?? exposure(c)?.requests ?? 0) > 0
          ? "breaking_observed" : "breaking_potential";
      }
      const order: State[] = ["unchanged", "changed_safe", "breaking_potential", "breaking_observed"];
      if (!current || order.indexOf(state) > order.indexOf(current)) byOp.set(key, state);
    }
    const calls = new Map(bundle.contract.operations.map((o) => [operationKey(o), o]));
    const keys = new Set([...bundle.explore.operations.map(operationKey), ...byOp.keys()]);
    return [...keys].map((key) => ({
      key,
      group: `/${key.split(" ")[1].split("/")[1] ?? ""}`,
      state: byOp.get(key) ?? ("unchanged" as State),
      calls: calls.get(key)?.calls ?? 0,
      clients: calls.get(key)?.clients ?? 0,
    }));
  }, [bundle]);

  useEffect(() => {
    if (!container.current) return;
    const colors: Record<State, string> = {
      breaking_observed: css("--critical"),
      breaking_potential: css("--serious"),
      changed_safe: css("--observed"),
      unchanged: css("--never"),
    };
    const max = Math.max(1, ...operations.map((o) => o.calls));
    // The map is the path tree: /recipes above /recipes/{id} above /recipes/{id}/ratings. Each path is
    // represented by its GET (or first) operation; its other methods hang beside it.
    const paths = [...new Set(operations.map((o) => o.key.split(" ")[1]))];
    const representative = (path: string) => {
      const here = operations.filter((o) => o.key.split(" ")[1] === path);
      return (here.find((o) => o.key.startsWith("GET ")) ?? here[0]).key;
    };
    const parentOf = (path: string) => {
      const segments = path.split("/").filter(Boolean);
      for (let n = segments.length - 1; n > 0; n--) {
        const candidate = `/${segments.slice(0, n).join("/")}`;
        if (paths.includes(candidate)) return candidate;
      }
      return null;
    };
    const edges: { data: { id: string; source: string; target: string } }[] = [];
    for (const o of operations) {
      const path = o.key.split(" ")[1];
      const rep = representative(path);
      const parent = parentOf(path);
      const source = o.key !== rep ? rep : parent ? representative(parent) : "root";
      edges.push({ data: { id: `${source}>${o.key}`, source, target: o.key } });
    }
    const cy = cytoscape({
      container: container.current,
      elements: [
        { data: { id: "root", label: bundle.explore.title ?? "API", root: 1 } },
        ...operations.map((o) => ({
          data: {
            id: o.key,
            label: o.key,
            color: colors[o.state],
            size: 22 + 40 * (Math.log(o.calls + 1) / Math.log(max + 1)),
          },
        })),
        ...edges,
      ],
      style: [
        {
          selector: "node[size]",
          style: {
            "background-color": "data(color)",
            width: "data(size)",
            height: "data(size)",
            label: "data(label)",
            "font-size": 11,
            color: css("--text-primary"),
            "text-valign": "bottom",
            "text-margin-y": 5,
            "text-wrap": "wrap",
            "text-max-width": "120px",
            "border-width": 2,
            "border-color": css("--surface-1"),
          },
        },
        {
          selector: "node[root]",
          style: {
            shape: "round-rectangle",
            width: 12,
            height: 12,
            "background-color": css("--text-secondary"),
            label: "data(label)",
            "font-size": 11,
            color: css("--text-secondary"),
            "text-valign": "top",
            "text-margin-y": -6,
          },
        },
        {
          selector: "edge",
          style: { width: 1.5, "line-color": css("--border"), "curve-style": "bezier", "target-arrow-shape": "none" },
        },
        { selector: "node:selected", style: { "border-color": css("--text-primary"), "border-width": 3 } },
      ],
      layout: { name: "breadthfirst", directed: true, roots: ["root"], padding: 30, spacingFactor: 1.25, animate: false } as cytoscape.LayoutOptions,
      userZoomingEnabled: false,
    });
    cy.on("tap", "node[size]", (event) => setPicked(event.target.id()));
    return () => cy.destroy();
  }, [operations, bundle.explore.title]);

  const selected = operations.find((o) => o.key === picked);
  const changes = bundle.report.changes.filter((c) => picked !== null && operationKey(c.operation) === picked);

  return (
    <section className="card">
      <h2>API map</h2>
      <p className="sub">
        {bundle.explore.title ?? "Spec"} {bundle.explore.version ?? ""}: {bundle.explore.stats.operations} operations.
        Size is traffic; colour is what the candidate version does to the operation.
      </p>
      <div className="legend">
        {(Object.keys(STATE_TEXT) as State[]).map((s) => (
          <span key={s} className="key">
            <span className="swatch" style={{
              borderRadius: "50%",
              background: s === "breaking_observed" ? "var(--critical)" : s === "breaking_potential" ? "var(--serious)"
                : s === "changed_safe" ? "var(--observed)" : "var(--never)",
            }} />
            {STATE_TEXT[s]} ({operations.filter((o) => o.state === s).length})
          </span>
        ))}
      </div>
      <div ref={container} className="cy" role="img"
        aria-label="Map of operations as a tree of paths. The table below lists the same operations." />
      {selected && (
        <p style={{ marginBottom: 0 }}>
          <strong className="mono">{selected.key}</strong>: {STATE_TEXT[selected.state]}; {fmt(selected.calls)} calls
          from {selected.clients} clients.
          {changes.length > 0 && ` ${changes.filter((c) => c.breaking).length} breaking and ${changes.filter((c) => !c.breaking).length} safe changes.`}
        </p>
      )}
      <details style={{ marginTop: 10 }}>
        <summary>As a table</summary>
        <table>
          <thead><tr><th>Operation</th><th>Candidate</th><th className="num">Calls</th><th className="num">Clients</th></tr></thead>
          <tbody>
            {[...operations].sort((a, b) => b.calls - a.calls).map((o) => (
              <tr key={o.key}>
                <td className="mono">{o.key}</td>
                <td>{STATE_TEXT[o.state]}</td>
                <td className="num">{fmt(o.calls)}</td>
                <td className="num">{o.clients}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
      {Object.keys(bundle.explore.stats.unsupported_by_construct).length > 0 && (
        <p className="secondary">
          Constructs not modelled exactly in this spec:{" "}
          {Object.entries(bundle.explore.stats.unsupported_by_construct).map(([k, n]) => `${k} (${n})`).join(", ")}.
        </p>
      )}
    </section>
  );
}
