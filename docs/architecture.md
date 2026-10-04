# Axon: architecture

```
OpenAPI v1 ─┐
            ├─► Diff ────────────────────────────┐
OpenAPI v2 ─┘                                    ▼
                                           Impact engine ─► report (JSON) ─► web UI / CLI gate
HAR / JSONL ─► Sanitiser ─► events ─► Matcher ─► Cells (counters) ─► Aggregate ─┤
              (shapes only)                                                     └─► Drift
```

## Modules

| Module | Depends on | What it holds |
|---|---|---|
| `axon-core` | Jackson, SnakeYAML | Everything that decides anything: parser, schema model, diff, matcher, sanitiser, aggregation, drift, impact, report writer. **No Spring.** |
| `axon-cli` | core | `impact`, `check`, `diff`, `sanitise`, `explore`, `bundle`. One shaded jar. |
| `axon-server` | core, Spring Boot, Postgres | HTTP API: workspaces, ingest, replay, analysis. The only module that uses Spring. |
| `axon-eval` | core | Experiments, traffic simulator, demo service and clients. Not shipped. |
| `web` | (React, d3-sankey, Cytoscape) | The UI. Talks to the server, or to static JSON when the server is asleep. |

The rule that keeps this honest: the CLI, the server and the experiments all call the same `axon-core` functions. An integration test checks that the report computed over HTTP from Postgres equals the one computed by the CLI from a file, row for row.

## The spec model (`core/spec`)

A spec is parsed into a **graph of schema nodes**, not a tree: a `$ref` resolves to the same node, so shared components are shared and recursive schemas are cycles. Composition is resolved lazily:

- `allOf` parts are merged.
- `oneOf`/`anyOf` are approximated as a union: every variant's properties, required only if every variant requires them. "X or null" is exact.
- Anything recognised but not modelled is recorded in `unsupported` with a location, a reason and whether it was ignored or approximated.

YAML is built straight from parser events with JSON-like scalar rules, because YAML 1.1 would turn `yes` into a boolean and `2022-11-28` into a date. Parsing runs on a thread with a large stack: schema graphs nest thousands deep.

Measured limits are in [m1-parse-survey.md](m1-parse-survey.md): a 25.8 MB spec fits in a 256 MB heap; uploads are capped at 32 MB (8 MB on the public server).

## Diff (`core/diff`)

Two specs are compared operation by operation (paths matched with parameter names blanked). Schemas are compared as a **graph of pairs**:

1. Each pair of (baseline schema, candidate schema) is compared **once**. Its own changes are stored on the pair; the pairs below it become edges.
2. Pairs that lead to a change are marked.
3. Per operation, changes are placed under field paths in two breadth-first passes: one that visits every marked pair once (complete, linear), one that adds further paths within a budget. Breaking changes are placed before safe ones.

This shape exists because the first version walked paths depth-first and did not finish on Stripe's spec. See the evaluation's "three things the evaluation broke".

Compatibility is directional: a request change is breaking when the candidate rejects something the baseline accepted; a response change when the candidate may send something the baseline did not allow, or stops guaranteeing something it did.

## Traffic (`core/traffic`)

- **Sanitiser.** A raw exchange becomes an event that holds field paths and types and nothing else, except the values of fields the spec declares as enums (capped at 16). Client identity is a salted hash. It runs in the CLI and, re-implemented in TypeScript, in the browser, so raw bodies never reach a server.
- **Matcher.** A trie over path segments. At each level it tries a literal, then a mixed segment (`{id}.json`), then a bare parameter, and backtracks: `/users/me` matches the literal template, `/users/me/posts` falls back to `/users/{id}/posts`.

## Aggregation (`core/observe`)

An `Aggregate` is counts with first and last timestamps, by operation, client, status, field, type and enum value. Every update is a sum, a minimum or a maximum, so the result does not depend on event order.

`Cells` is the same aggregate taken apart into one counter per key and UTC day. That is the stored form:

```
events ──► Cells (a map of counters) ──upsert──► agg_cell (Postgres)
                                                     │
                              Aggregate ◄──fold──────┘   (for a day range)
```

A test rebuilds the aggregate of the real demo capture from cells and compares it with the directly built one; another shuffles and re-batches the events and compares the cells.

## Server (`axon-server`)

Three tables:

| Table | Purpose |
|---|---|
| `workspace` | One API under observation: current spec, hashes of its two keys, limits. |
| `traffic_event` | Append-only log of sanitised events. Kept so aggregates can be rebuilt. |
| `agg_cell` | The counters. Primary key is `(workspace, md5 of the logical key)` because field paths can exceed what a btree entry allows. |

**Ingest** parses each line with the same parser the CLI uses (which keeps only schema fields, so anything else in the line is dropped), then in one transaction inserts the events and upserts their cells (`count = count + excluded.count`, `least`/`greatest` for the time span). A per-workspace advisory lock serialises writers, which also rules out upsert deadlocks.

**Replay** deletes a workspace's cells and re-runs the aggregator over its stored events in pages. It runs when the spec changes, because matching depends on the spec. Live and replayed counters are compared by fingerprint in the tests and in E4.

**No queue.** The workload is small and commutative; batch upserts are enough. Ingestion sits behind one service class so a queue could be put in front of it.

**Keys and limits.** Two keys per workspace, both shown once and stored as hashes: an ingest key that can only add events, and an admin key that reads results. Limits, all configurable: body size (2 MB), events per request (1,000), events per workspace (200,000), requests per second per workspace (20), retention (90 days, enforced hourly), number of workspaces (200).

## Web UI (`web`)

Five views under the three questions, with Impact as the landing page:

| Area | View | Encoding |
|---|---|---|
| Change | **Blast radius** (Sankey: change → operation → client) | width = requests; solid = observed, dashed and lighter = potential |
| Change | Clients × request fields | cell = requests carrying the field; selecting a field or change highlights the clients it breaks |
| Reality | Declared vs observed, with drift | documented and used / documented, never seen / undocumented |
| Reality | Last seen per client | one cell per day; shows the rare callers that percentages hide |
| Promise | API map (Cytoscape) | size = traffic; colour = what the candidate does to the operation |

A confidence inset on the Impact page shows the learning curve from E2 with the selected operation's request count marked.

The UI loads `web/public/demo/*.json` (written by `axon bundle`) by default, so it works with no backend. "Use your own API" switches to live mode.

## Deployment shape

Not deployed by this repository; designed for a static host for `web` and a 512 MB container for `axon-server`.

- `Dockerfile` builds the server; `JAVA_OPTS` defaults to a 256 MB heap.
- Environment: `AXON_DB_URL`, `AXON_DB_USER`, `AXON_DB_PASSWORD`, `AXON_CORS_ORIGINS` (the UI's origin), and the `AXON_MAX_*` limits. `PORT` is honoured.
- The UI needs `VITE_AXON_API` at build time for live mode; without it only the demo works.
- The JVM runs in UTC (`-Duser.timezone=UTC`); Postgres rejects some legacy zone names a JVM may default to.

## What is deliberately absent

GraphQL, gRPC, auth flows, scanning client source code, header and cookie observation (headers are read only for client identity), value constraints, `format`, response headers. Each absence is stated where it bites: in the report's limitations, in `unsupported`, or in the evaluation.
