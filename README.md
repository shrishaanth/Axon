# Axon

**API change-impact analysis.** Diff tools say "removing `phone` is breaking". Axon says *who would break*: it ranks the breaking changes between two OpenAPI versions by the clients that observed traffic shows are affected, and reports where the running API has drifted from its documentation.

> What does my API promise, what does it actually do, and what happens if I change it?

| | Source | What you get |
|---|---|---|
| **Promise** | OpenAPI 3.0/3.1 spec | Explorer |
| **Reality** | Observed traffic (HAR or JSONL) | Observed contract, drift |
| **Change** | Spec v1 → v2 | Diff and **impact analysis** |

## What it looks like

```text
17 breaking changes of 24; 10898 of 10958 events matched; 10 clients active in the last 7 days

  1. HIGH                Query parameter 'sort' removed from GET /recipes.
     observed affected: 3 clients, 1915 requests, last seen 2026-09-30   (spec-only rank 16)
  2. HIGH                Operation GET /export/recipes removed.
     observed affected: 3 clients, 181 requests, last seen 2026-09-30    (spec-only rank 17)
  ...
 11. potential CRITICAL  Field $.items[].legacy_slug in the response 200 of GET /recipes removed.
     potentially affected: 6 clients, 4916 requests                      (spec-only rank 1)
```

(Output abridged; the full text is in [eval/demo/report.txt](eval/demo/report.txt).) The two changes a spec-only ranking puts last are the two with the most certainly-affected clients. This run is real HTTP traffic captured through mitmproxy from a demo service; see [docs/m3-end-to-end.md](docs/m3-end-to-end.md).

## The central limitation

Traffic shows who **sends** a request field and who **calls** an operation. It does not show who **reads** a response field. So request-side changes and removed operations are *observed*; response-side changes are only *potentially affected*. Axon never blends the two. Measured cost: about three of four clients listed as potentially affected do not read the changed field.

## What has been measured

Method written before results: [docs/evaluation.md](docs/evaluation.md). Short version, losses included:

- **Ranking** (100 held-out synthetic populations): Kendall tau with the true affected-client count is 0.69 for Axon, 0.48 for ranking by request volume, -0.03 for spec-only ranking, 0.00 for random.
- **Counts are lower bounds.** Axon never names an unaffected client, but with a 7-day window it sees 69% of the truly affected ones (85% at 30 days, 51% when client activity is heavy-tailed).
- **Diffing is not the contribution.** Against `oasdiff` on 200 real version pairs (Stripe, GitHub, Twilio, APIs.guru), Axon reports 73% of what `oasdiff` calls breaking, and 84% of the kinds it models. It does not model `format`, value constraints or changes to `oneOf`/`anyOf` lists, and has one confirmed blind spot in union schemas.
- **Observed contract**: field recall 0.93 after 100 requests per operation, 0.998 after 100,000, matching the predicted curve.
- **Not yet done**: the hand-labelled sample (E1c, waiting for labels) and ingestion throughput (E4, milestone M5).

## Status

| Milestone | State |
|---|---|
| M0 definitions, evaluation method, schemas, CI | done |
| M1 OpenAPI parser and explorer | done: [docs/m1-parse-survey.md](docs/m1-parse-survey.md) |
| M2 diff engine, E1 | done except E1c labels |
| M3 traffic → observed contract → impact | done: [docs/m3-end-to-end.md](docs/m3-end-to-end.md) |
| M4 drift, inference, E2, E3 | done |
| M5 live ingest, replay, E4 | not started |
| M6 web UI | not started |
| M7 CLI gate, release | not started |

## Use

Needs Java 17 and Maven.

```bash
mvn -q -DskipTests clean package
```

```bash
java -jar axon-cli/target/axon-cli-0.1.0-SNAPSHOT-all.jar impact --baseline eval/demo/recipes-v1.yaml --candidate eval/demo/recipes-v2.yaml --usage eval/demo/usage.jsonl --identity-header X-Client-Id
```

Other commands: `diff` (changes between two specs), `sanitise` (HAR to shape-only events; values never reach the output), `explore` (what a spec declares). Run the jar with `--help` for options. The `check` gate with a threshold is planned for M7.

## Layout

| Path | What |
|---|---|
| `axon-core` | Parser, model, diff, matcher, aggregation, drift, impact. No Spring. |
| `axon-cli` | Command line. |
| `axon-eval` | Experiments, traffic simulator, demo service and clients. |
| `docs` | [metrics](docs/metrics.md), [evaluation](docs/evaluation.md), [report schema](docs/report-schema.md), [decisions](docs/decisions.md) |
| `eval` | Raw results, datasets manifests, the demo capture, scripts to rebuild everything. |
| `schemas`, `examples`, `tools/schema-check` | JSON schemas for events and reports, validated in CI. |

```bash
mvn -q verify
```

```bash
cd tools/schema-check && npm ci && npm run check
```
