# M3: the batch slice, end to end

Goal of the milestone: show the core idea working on one real change, from captured traffic to a ranked impact report. Details of the dataset are in [`eval/demo/README.md`](../eval/demo/README.md).

```
demo.har ──► axon sanitise ──► usage.jsonl ──► axon impact ──► report.json
(mitmproxy)   (shapes only)     (10,958 events)   (v1 → v2)      (17 breaking changes, ranked)
```

## What came out

The spec-only view (what a diff tool gives you) and the usage-aware view disagree sharply on what to look at first:

| Axon rank | Change | Evidence | Affected | Severity | Spec-only rank |
|---|---|---|---|---|---|
| 1 | `legacy_slug` removed from `GET /recipes` 200 | potential | up to 6 clients | CRITICAL | 1 |
| 2 | query parameter `sort` removed from `GET /recipes` | **observed** | 3 clients, 1,915 requests | HIGH | 16 |
| 3 | `GET /export/recipes` removed | **observed** | 3 clients, 181 requests | HIGH | 17 |
| 4 | `difficulty` no longer accepts `expert` (`POST /recipes`) | **observed** | 3 clients | HIGH | 8 |
| 5 | `servings` became required (`POST /recipes`) | **observed** | 2 clients | HIGH | 7 |
| ... | | | | | |
| 14 | `minutes` number → integer (`POST /recipes`) | **observed** | 2 clients, last seen 12 days ago | LOW | 6 |
| 15-17 | the same three request changes on `PUT /recipes/{id}` | **observed** | 0 clients | NONE_OBSERVED | 10-12 |

Full output: [`eval/demo/report.txt`](../eval/demo/report.txt).

The two changes the spec-only baseline ranks last (16 and 17 of 17) are the two with the most certainly-affected clients. The three it ranks 10-12 affect nobody: the only `PUT` caller already sends `servings`, whole-number `minutes` and an allowed `difficulty`.

## Checked against ground truth

The demo clients record what they send and which response fields their code reads. `eval/scripts/check_demo_truth.py` derives the truly affected clients from that record, in Python, without using Axon's code:

| | Result |
|---|---|
| Observed rows whose client set is exactly right | **10 of 10** |
| Potential rows that contain every true reader | **7 of 7** |
| Client-change pairs listed as potential that truly read the field | **4 of 24 (17%)** |

The last line is the cost of the central limitation, measured: on this dataset, **five out of six clients listed as "potentially affected" by a response change do not read the changed field.** Rank 1 above is the clearest case: six clients call `GET /recipes`, one reads `legacy_slug`. Axon cannot know that from traffic, labels the row "potential", and ranks it first because the ladder puts severity before evidence class. Whether that ordering is the right one is exactly what E3 measures.

Drift, same run: all six differences built into the service were found (`internal_score`, `servings` as string, 429, `/healthz`, `debug`, and `subtitle` never returned). Counted properly in E2.

## Honest limits of this milestone

- **One change, one author.** I wrote the service, the clients, both specs and the checker. A 10 of 10 here shows the pipeline is consistent with my own definitions on real HTTP; it does not show it works on someone else's API.
- **Timestamps are simulated** through a request header (the capture took 40 seconds).
- **Thirteen clients** is small. Every affected set fits in the report's top-ten list; nothing here tests scale or the rare-client problem. M4 does.
- The check covers request-side and response-side field changes and removed operations. It does not cover header parameters, non-JSON bodies or status-code removals, none of which occur in this change.
