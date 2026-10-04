import { useState } from "react";
import { createWorkspace, ingest, loadLive, uploadSpec, type Bundle, type LiveSettings } from "../api";
import { sanitiseHar } from "../sanitise";
import { fmt } from "../ui";

const DEFAULT_API = (import.meta.env.VITE_AXON_API as string | undefined) ?? "";

async function readFile(input: HTMLInputElement | null): Promise<{ name: string; text: string } | null> {
  const file = input?.files?.[0];
  return file ? { name: file.name, text: await file.text() } : null;
}

/**
 * Live mode: connect to an Axon API, upload the current spec and traffic, and analyse a candidate spec.
 * HAR files are reduced to shapes here in the browser; only shapes are uploaded.
 */
export function Setup({ onLoaded }: { onLoaded: (bundle: Bundle) => void }) {
  const [api, setApi] = useState(DEFAULT_API);
  const [workspace, setWorkspace] = useState("");
  const [adminKey, setAdminKey] = useState("");
  const [ingestKey, setIngestKey] = useState("");
  const [identityHeader, setIdentityHeader] = useState("X-Client-Id");
  const [salt, setSalt] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const settings: LiveSettings = { api, workspace, adminKey };

  const run = async (work: () => Promise<string | void>) => {
    setBusy(true);
    setError(null);
    try {
      const result = await work();
      if (result) setMessage(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <section className="card">
        <h2>1. Connect</h2>
        <p className="sub">
          A workspace is one API under observation. Creating one returns two keys, shown once: the ingest key can
          only add events; the admin key reads results. Keys stay in this page's memory and are not stored.
        </p>
        <div className="form-grid">
          <label htmlFor="api">API base URL</label>
          <input id="api" type="text" value={api} onChange={(e) => setApi(e.target.value)} placeholder="https://your-axon-api.example" />
          <label htmlFor="ws">Workspace id</label>
          <input id="ws" type="text" value={workspace} onChange={(e) => setWorkspace(e.target.value)} />
          <label htmlFor="admin">Admin key</label>
          <input id="admin" type="password" value={adminKey} onChange={(e) => setAdminKey(e.target.value)} autoComplete="off" />
          <label htmlFor="ingest">Ingest key</label>
          <input id="ingest" type="password" value={ingestKey} onChange={(e) => setIngestKey(e.target.value)} autoComplete="off" />
        </div>
        <p>
          <button className="plain" disabled={busy || !api} onClick={() => run(async () => {
            const name = window.prompt("Name for the new workspace", "my-api");
            if (!name) return;
            const created = await createWorkspace(api, name);
            setWorkspace(created.id);
            setAdminKey(created.admin_key);
            setIngestKey(created.ingest_key);
            return `Workspace created. Copy the keys now; they are not shown again. Admin: ${created.admin_key}  Ingest: ${created.ingest_key}`;
          })}>Create a new workspace</button>
        </p>
      </section>

      <section className="card">
        <h2>2. Current spec</h2>
        <p className="sub">The OpenAPI 3.0/3.1 document your API runs today. Traffic is matched against it.</p>
        <input id="baseline" type="file" accept=".json,.yaml,.yml" aria-label="Current spec file" />{" "}
        <button className="plain" disabled={busy || !workspace || !adminKey} onClick={() => run(async () => {
          const file = await readFile(document.getElementById("baseline") as HTMLInputElement);
          if (!file) throw new Error("Choose a spec file first.");
          const result = await uploadSpec(settings, file.name, file.text);
          return `Spec stored: ${result.operations} operations.`;
        })}>Upload</button>
      </section>

      <section className="card">
        <h2>3. Traffic</h2>
        <p className="sub">
          A HAR file from a proxy or browser. It is reduced to field paths and types <strong>in this browser</strong>;
          request and response values, tokens and raw client identities are not uploaded. Paths are redacted by
          heuristic (numbers, UUIDs, long tokens): a short word used as an id would survive, so check your paths.
          No values are kept at all, so a change that narrows an enum shows as "potential" here; the command-line
          sanitiser, given the spec, keeps declared enum values and can show it as observed.
        </p>
        <div className="form-grid">
          <label htmlFor="header">Client identity header</label>
          <input id="header" type="text" value={identityHeader} onChange={(e) => setIdentityHeader(e.target.value)} placeholder="leave empty for none" />
          <label htmlFor="salt">Salt</label>
          <input id="salt" type="text" value={salt} onChange={(e) => setSalt(e.target.value)} placeholder="any secret string; keeps pseudonyms stable" />
          <label htmlFor="har">HAR file</label>
          <input id="har" type="file" accept=".har,.json" />
        </div>
        <p>
          <button className="plain" disabled={busy || !ingestKey} onClick={() => run(async () => {
            const file = await readFile(document.getElementById("har") as HTMLInputElement);
            if (!file) throw new Error("Choose a HAR file first.");
            const { lines, skipped } = await sanitiseHar(JSON.parse(file.text), { identityHeader, salt });
            const result = await ingest(api, ingestKey, lines);
            return `${fmt(result.accepted)} events uploaded as shapes${result.rejected ? `, ${result.rejected} rejected` : ""}${skipped ? `, ${skipped} unreadable entries skipped` : ""}.`;
          })}>Sanitise and upload</button>
        </p>
      </section>

      <section className="card">
        <h2>4. Candidate spec</h2>
        <p className="sub">The version you are thinking of shipping.</p>
        <input id="candidate" type="file" accept=".json,.yaml,.yml" aria-label="Candidate spec file" />{" "}
        <button className="primary" disabled={busy || !workspace || !adminKey} onClick={() => run(async () => {
          const file = await readFile(document.getElementById("candidate") as HTMLInputElement);
          if (!file) throw new Error("Choose the candidate spec first.");
          onLoaded(await loadLive(settings, file.text, file.name));
        })}>Analyse impact</button>
      </section>

      {busy && <p className="secondary" role="status">Working…</p>}
      {message && <div className="banner" role="status" style={{ wordBreak: "break-all" }}>{message}</div>}
      {error && <div className="banner error" role="alert"><strong>That did not work.</strong> {error}</div>}
    </>
  );
}
