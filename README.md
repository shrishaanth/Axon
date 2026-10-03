# Axon

**API change-impact analysis.** Diff tools say "removing `phone` is breaking". Axon says *who would break*: it ranks breaking changes by real client impact ("18 of 24 clients, last seen today" versus "1 client, last seen 41 days ago") and shows where the real API has drifted from its documentation.

> What does my API promise, what does it actually do, and what happens if I change it?

| | Source | What you get |
|---|---|---|
| **Promise** | OpenAPI 3.0/3.1 spec | Explorer, structure view |
| **Reality** | Observed traffic (HAR, JSONL, live ingest) | Observed contract, drift |
| **Change** | Spec v1 → v2 | Diff and **impact analysis** |

## Status

**M0: definitions and evaluation method.** No implementation yet. The metrics and the evaluation method are written *before* any result exists, so results cannot quietly redefine them.

- [docs/metrics.md](docs/metrics.md): clients, window, severity, confidence, drift, the observed-versus-potential rule
- [docs/evaluation.md](docs/evaluation.md): experiments E1-E4, baselines, how unfavourable results are handled (results: none yet)
- [docs/report-schema.md](docs/report-schema.md): traffic-event and impact-report schemas
- [docs/decisions.md](docs/decisions.md): scope, stack, milestones, risks

## The central limitation

Traffic shows who **sends** a request field and who **calls** an operation. It does not show who **reads** a response field. So request-field and removed-operation impacts are *observed*, while response-field impacts are only *potentially affected*. Axon never blends the two, and the evaluation measures how much the second over-approximates.

## Planned CLI gate

```bash
contractlens check --baseline v1.yaml --candidate v2.yaml --usage usage.jsonl --threshold 10
```

The command name is a placeholder from the working title and will be renamed to match the project (`axon check`) before M7.

## Repository checks

```bash
cd tools/schema-check && npm ci && npm run check
```

Validates both JSON schemas against the examples in `examples/`, including documents that must be rejected.
