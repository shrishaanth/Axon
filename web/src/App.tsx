import { useEffect, useState } from "react";
import { loadDemo, type Bundle } from "./api";
import { ApiMap } from "./views/ApiMap";
import { ContractView } from "./views/ContractView";
import { Impact } from "./views/Impact";
import { Matrix } from "./views/Matrix";
import { Setup } from "./views/Setup";
import { Timeline } from "./views/Timeline";

type Tab = "impact" | "matrix" | "contract" | "timeline" | "map" | "setup";

// The three questions Axon answers, with Impact as the landing view.
const AREAS: { name: string; tabs: { id: Tab; label: string }[] }[] = [
  { name: "Change: what happens if I change it?", tabs: [{ id: "impact", label: "Impact" }, { id: "matrix", label: "Clients × fields" }] },
  { name: "Reality: what does it actually do?", tabs: [{ id: "contract", label: "Declared vs observed" }, { id: "timeline", label: "Last seen" }] },
  { name: "Promise: what does it promise?", tabs: [{ id: "map", label: "API map" }] },
];

const TAB_IDS: Tab[] = ["impact", "matrix", "contract", "timeline", "map", "setup"];

export function App() {
  const [bundle, setBundle] = useState<Bundle | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTabState] = useState<Tab>(() => {
    const fromHash = window.location.hash.slice(1) as Tab;
    return TAB_IDS.includes(fromHash) ? fromHash : "impact";
  });
  // each view has its own address, so it can be linked to
  const setTab = (next: Tab) => {
    setTabState(next);
    window.history.replaceState(null, "", `#${next}`);
  };

  useEffect(() => {
    loadDemo().then(setBundle).catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  return (
    <div className="app">
      <header className="top">
        <h1>Axon</h1>
        <span className="tagline">Which clients break if this API change ships?</span>
        <span className="source">
          {bundle && (
            <span className="pill" title={bundle.source === "demo"
              ? "A precomputed report on real traffic captured from a demo service. Works without the API."
              : "Computed by the Axon API from your workspace."}>
              {bundle.source === "demo" ? "demo data" : "live workspace"}
            </span>
          )}
          <button className="plain" onClick={() => setTab("setup")} aria-pressed={tab === "setup"}>Use your own API</button>
          {bundle?.source === "live" && (
            <button className="plain" onClick={() => { loadDemo().then(setBundle); setTab("impact"); }}>Back to demo</button>
          )}
        </span>
      </header>

      <nav className="areas" aria-label="Views">
        {AREAS.map((area) => (
          <div className="area" key={area.name}>
            <span className="area-name">{area.name}</span>
            <div className="tabs" role="tablist">
              {area.tabs.map((t) => (
                <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>{t.label}</button>
              ))}
            </div>
          </div>
        ))}
      </nav>

      <main>
        {tab === "setup" ? (
          <Setup onLoaded={(b) => { setBundle(b); setTab("impact"); }} />
        ) : error ? (
          <div className="banner error" role="alert"><strong>Could not load the report.</strong> {error}</div>
        ) : !bundle ? (
          <p className="secondary" role="status">Loading…</p>
        ) : tab === "impact" ? (
          <Impact bundle={bundle} />
        ) : tab === "matrix" ? (
          <Matrix bundle={bundle} />
        ) : tab === "contract" ? (
          <ContractView bundle={bundle} />
        ) : tab === "timeline" ? (
          <Timeline bundle={bundle} />
        ) : (
          <ApiMap bundle={bundle} />
        )}
      </main>
    </div>
  );
}
