# Axon: data and report schemas

Two machine-readable contracts, both validated in CI against the files in [`examples/`](../examples) (including cases that must be **rejected**):

| Contract | File | Produced by | Consumed by |
|---|---|---|---|
| Sanitised traffic event (one JSONL line) | [`schemas/traffic-event.schema.json`](../schemas/traffic-event.schema.json) | the sanitiser (CLI/browser), HAR converter | ingest endpoint, aggregator, `--usage` |
| Impact and drift report | [`schemas/impact-report.schema.json`](../schemas/impact-report.schema.json) | the impact engine | web UI, CLI gate, tests |

Terms (`observed`, `potential`, severity, confidence) are defined in [metrics.md](metrics.md).

## 1. Traffic event

```json
{"v":1,"ts":"2026-09-30T08:15:02Z","method":"POST","operation":"POST /users","status":201,
 "latency_ms":42.5,"client":"a1b2c3d4e5f60718",
 "request":{"body":[{"path":"$.email","types":["string"]},{"path":"$.plan","types":["string"],"values":["free"]}]},
 "response":{"body":[{"path":"$.id","types":["integer"]}]},
 "sanitiser":{"version":"0.1.0","salt_id":"ws-1"}}
```

What is kept and what is not:

| Kept | Dropped |
|---|---|
| timestamp, method, status, latency | request/response **values** |
| operation key (when matched locally) or a path with value-like segments replaced by `{*}` | raw IDs, tokens, emails in paths, queries or bodies |
| field **paths and types** | header values (except as client identity input) |
| values of **spec-declared enums**, at most 16 per field | free-text values, always |
| client **pseudonym** (salted hash) | the raw API key / header value |

Design notes:

- The schema has `additionalProperties: false` everywhere, so a stray `"value"` key is rejected by validation, and CI tests that.
- Either `operation` or `path` is required. Matching locally in the sanitiser (when a spec is supplied) means raw paths never leave the user's machine. Without a spec the path fallback is heuristic: digits, UUIDs, long hex strings and long tokens become `{*}`. A short word used as an ID (a username in the path) will survive; the docs say so.
- Bodies are capped at 500 fields and depth 10; `truncated: true` flags a cut.
- The `client` pseudonym can be reversed by anyone holding the salt and a guess; it is not anonymisation.
- Query parameters appear as `$.name` paths under `request.query`.

HAR files are not a separate internal format: the sanitiser converts HAR entries into these events, then the pipeline is identical for HAR, JSONL and live ingest.

## 2. Impact report

Top-level sections:

| Key | Meaning |
|---|---|
| `inputs` | Baseline/candidate spec identity (path, sha256, title, version) and a usage summary including **matched vs unmatched** event counts. |
| `config` | The window, client identity mode, every threshold, critical clients, server-error rule. Everything needed to reproduce the severities. |
| `summary` | Counts. `by_severity` has separate `observed` and `potential` buckets, deliberately never summed. |
| `changes` | One entry per change: kind, breaking flag, operation, location, evidence, exposure, severity, rank, spec-only rank, confidence. |
| `drift` | Spec-versus-traffic findings (metrics.md §10). |
| `unsupported` | Constructs recognised but not modelled, each with a location and reason. |
| `limitations` | Plain-language caveats that apply to this specific report. |

### The non-blending rule, as schema

A change has `evidence` of `observed`, `potential` or `none`, fixed by its `kind`:

- `observed` ⇒ must have `observed_affected`, must not have `potentially_affected`.
- `potential` ⇒ must have `potentially_affected`, must not have `observed_affected`.
- `none` (safe change) ⇒ neither; `severity`, `rank` and `spec_only_rank` are `null`.

The two exposure blocks have the same inner shape but different names, so a consumer cannot add them together without choosing to. The CI test suite includes four documents that each break one of these rules and must be rejected.

### Reading a row

```text
c1  POST /users  request field `phone` removed        evidence: observed
    observed_affected: 18 clients, 9120 requests, share_recent 0.75, last seen 2026-09-30
    severity CRITICAL   rank 1   spec-only rank 2

c2  GET /users/{id}  response field `legacy_id` removed   evidence: potential
    potentially_affected: 1 client, 40 requests, last seen 2026-08-20 (41 days before window end)
    severity DORMANT    rank 2   spec-only rank 1
```

The spec-only baseline puts `c2` first because it touches more operations; usage ranking puts `c1` first because 18 of 24 active clients send the field. Contrasts like this one are what E3 measures. (Illustrative numbers; a real report is in [`eval/demo/report.json`](../eval/demo/report.json).)

### Versioning

`schema_version` is `"0.2"` (0.1 was the M0 draft; 0.2 added change kinds, `source`, `approximated`, `evidence_note`, `operations_touched` and `handling`). Breaking changes to either schema before then are allowed but must be logged in the amendment tables of the docs that depend on them.
