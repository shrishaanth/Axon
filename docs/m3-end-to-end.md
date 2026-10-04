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
| 1 | Query parameter 'sort' removed from GET /recipes | **observed** | 3 clients, 1,915 requests | HIGH | 16 |
| 2 | Operation GET /export/recipes removed | **observed** | 3 clients, 181 requests | HIGH | 17 |
| 3 | Field $.difficulty in the request body of POST /recipes allows fewer values | **observed** | 3 clients, 330 requests | HIGH | 9 |
| 4 | Field $.servings in the request body of POST /recipes became required | **observed** | 2 clients, 288 requests | HIGH | 6 |
| 5 | Field $.notes in the request body of POST /recipes removed | **observed** | 2 clients, 288 requests | HIGH | 7 |
| 6 | Field $.notes in the request body of PUT /recipes/{id} removed | **observed** | 1 client, 20 requests | HIGH | 11 |
| 7 | Field $.minutes in the request body of POST /recipes changed type from number to integer | **observed** | 2 clients, 204 requests | LOW | 8 |
| 8 | Field $.servings in the request body of PUT /recipes/{id} became required | **observed** | 0 clients, 0 requests | NONE_OBSERVED | 10 |
| 9 | Field $.minutes in the request body of PUT /recipes/{id} changed type from number to integer | **observed** | 0 clients, 0 requests | NONE_OBSERVED | 12 |
| 10 | Field $.difficulty in the request body of PUT /recipes/{id} allows fewer values | **observed** | 0 clients, 0 requests | NONE_OBSERVED | 13 |
| 11 | Field $.items[].legacy_slug in the response 200 of GET /recipes removed | potential | up to 6 clients, 4,916 requests | CRITICAL | 1 |
| 12 | Field $.legacy_slug in the response 201 of POST /recipes removed | potential | up to 6 clients, 1,108 requests | HIGH | 2 |
| 13 | Field $.legacy_slug in the response 200 of GET /recipes/{id} removed | potential | up to 4 clients, 2,084 requests | HIGH | 4 |
| 14 | Field $.comment in the response 201 of POST /recipes/{id}/ratings changed type from string to null\|string | potential | up to 3 clients, 1,218 requests | HIGH | 15 |
| 15 | Field $.legacy_slug in the response 200 of GET /recipes/featured removed | potential | up to 2 clients, 721 requests | HIGH | 3 |
| 16 | Field $[].comment in the response 200 of GET /recipes/{id}/ratings changed type from string to null\|string | potential | up to 2 clients, 601 requests | HIGH | 14 |
| 17 | Field $.legacy_slug in the response 200 of PUT /recipes/{id} removed | potential | up to 1 client, 20 requests | HIGH | 5 |

Full output: [`eval/demo/report.txt`](../eval/demo/report.txt).

The two changes the spec-only baseline ranks last (16 and 17 of 17) are the two with the most certainly-affected clients. Three changes it ranks mid-table (10, 12 and 13) affect nobody: the only `PUT` caller already sends `servings`, whole-number `minutes` and an allowed `difficulty`.

The table also shows the rough edge of the current ordering (observed rows first, see [metrics.md](metrics.md) §6): three rows that affect no one sit above the first potential row, which may affect up to six clients and truly affects one. The first version of this report ranked by severity before evidence class and put that potential row at the top instead. Neither is clearly right; E3 compares them.

## Checked against ground truth

The demo clients record what they send and which response fields their code reads. `eval/scripts/check_demo_truth.py` derives the truly affected clients from that record, in Python, without using Axon's code:

| | Result |
|---|---|
| Observed rows whose client set is exactly right | **10 of 10** |
| Potential rows that contain every true reader | **7 of 7** |
| Client-change pairs listed as potential that truly read the field | **4 of 24 (17%)** |

The last line is the cost of the central limitation, measured: on this dataset, **five out of six clients listed as "potentially affected" by a response change do not read the changed field.** The `legacy_slug` row for `GET /recipes` is the clearest case: six clients call the operation, one reads the field. Axon cannot know that from traffic and labels the row "potential".

Drift, same run: all six differences built into the service were found (`internal_score`, `servings` as string, 429, `/healthz`, `debug`, and `subtitle` never returned). Counted properly in E2.

## Honest limits of this milestone

- **One change, one author.** I wrote the service, the clients, both specs and the checker. A 10 of 10 here shows the pipeline is consistent with my own definitions on real HTTP; it does not show it works on someone else's API.
- **Timestamps are simulated** through a request header (the capture took 40 seconds).
- **Thirteen clients** is small. Every affected set fits in the report's top-ten list; nothing here tests scale or the rare-client problem. M4 does.
- The check covers request-side and response-side field changes and removed operations. It does not cover header parameters, non-JSON bodies or status-code removals, none of which occur in this change.
