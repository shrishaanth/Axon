# Axon: decisions and scope

Short records of choices made at M0, with the reason and what would reverse them.

## Identity

> **What does my API promise, what does it actually do, and what happens if I change it?**

| Area | Source | Features |
|---|---|---|
| Promise | OpenAPI spec | Explorer, structure view |
| Reality | observed traffic | Observed contract, drift |
| Change | v1 → v2 | Diff and **impact analysis (the flagship)** |

Every README and demo leads with impact. Core loop: `spec → diff → traffic → observed contract → impact`.

## D1. Name: Axon

Chosen by the author. **Collision to be aware of:** "Axon" is also the name of AxonIQ's well-known Java event-sourcing/CQRS framework (Axon Framework), and of other products. Searching "Axon Java" or "Axon API" will mostly find that. Mitigation: always write the descriptor next to the name ("Axon: API change-impact analysis"), name the repo `axon-api` if `axon` is taken, and keep Maven coordinates distinct (planned `groupId`: `io.github.<user>.axon`). Reverse if the confusion hurts discoverability; a rename before M7 is cheap, after is not.

## D2. Java 17 + Spring Boot, Postgres, React + TypeScript

Same stack as the previous project so effort goes into the problem, not the tooling. `axon-core` (parser, model, diff, matcher, inference, drift, impact) is **Spring-free** and testable alone. The Spring app, CLI and web UI depend on it, never the reverse.

## D3. No Kafka; no ML

The workload is small, commutative and file-based: aggregation is counting by `(operation, client, time bucket, field, type)`, so batch upserts into Postgres suffice. Replay works by re-running the aggregator over stored shape events (E4 verifies replay reproduces live aggregates exactly). Ingestion sits behind a small interface so a queue could be added later. No ML anywhere unless a measurement shows it helps.

## D4. Capacity designer, failure simulator, reliability planner are deferred

They need validation against a real reference service and would blur the story. They are a possible second project, not part of M0-M9.

## D5. Sanitise before sending

Raw bodies must never reach a public server. The sanitiser (shapes only; see [report-schema.md](report-schema.md)) runs in the CLI or the browser, and the server accepts only the sanitised event schema.

## D6. Scope

**In:** OpenAPI 3.0/3.1, JSON bodies, path/query parameters, HAR and JSONL input, live ingest endpoint, explorer, diff, observed contract, drift, impact, web UI, CLI. Headers only for client identity.

**Out:** GraphQL, gRPC, auth flows, scanning client source code.

**Optional stretch (M8-M9):** conformance checks against a live endpoint, generated negative tests, workflow tests, lint-style design checks.

## D7. Deployment constraints designed in (the author deploys)

Targets: Vercel (UI) and Render free tier (API, 512 MB).

- Cap upload size; measure memory on the largest public spec in M1.
- Public ingest needs per-project keys, size limits and rate limits.
- `traffic_events` gets per-workspace row caps and expiry.
- Ship a **precomputed demo** (sample spec, simulated traffic, precomputed report as static JSON) so the demo works while the API sleeps.
- The CLI ships as a jar on GitHub releases and needs a Java runtime.

## D8. Known risks

1. `oasdiff` may already be enough for diffing. If so, E1 will say so, and the claim shifts to usage-aware ranking. **E1 result: it is.** Axon's diff is a subset of `oasdiff`'s; the ranking is the contribution.
2. OpenAPI variety (`$ref`, `allOf`, `oneOf`, discriminators) is a time sink. Scope it, document unsupported constructs (metrics.md §11).
3. Route-matching ambiguity (`/users/me` vs `/users/{id}`): needs dedicated tests.
4. Real traffic is scarce, hence the simulator **and** the real demo service, reported separately.
5. Rare clients may not appear in a short window; that is surfaced through `min_detectable_rate_per_day`, not hidden.
6. A platform-style framing can blur the story. Lead with impact.

## Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Definitions, metrics, evaluation method, repo, CI | docs written before results ✔ |
| M1 | OpenAPI model, parser, explorer (CLI; the web view is M6) | 100 public specs parsed, failure report ✔ |
| M2 | Diff engine and E1 | compared with `oasdiff` ✔ (E1c labels pending) |
| M3 | **Batch slice:** HAR → observed profile → impact on one real change | core idea works end to end ✔ |
| M4 | Drift, inference, E2/E3 | learning curve and impact accuracy published ✔ |
| M5 | Live ingest endpoint, replay, E4 | throughput and replay measured |
| M6 | Web UI with five views | usable end to end |
| M7 | CLI gate, README with screenshots, architecture doc | **releasable** |
| M8 | Stretch: conformance, negative tests, workflow tests | optional |
| M9 | Stretch: lint-style design checks | optional |

Estimates are part-time, ±50%; M0-M7 ≈ 12-14 weeks.
