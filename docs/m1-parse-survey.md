# M1: parser survey on public specs

Raw files: [`eval/results/m1-parse-survey.json`](../eval/results/m1-parse-survey.json), [`eval/results/m1-memory.jsonl`](../eval/results/m1-memory.jsonl). Sample manifest with URLs and checksums: [`eval/datasets/s-pub.json`](../eval/datasets/s-pub.json). Rebuild with `python eval/scripts/fetch_specs.py`.

## Sample

100 specs drawn with a fixed seed (20261003) from the 1,521 documents in the APIs.guru directory that declare OpenAPI 3.x. Even positions were fetched as JSON and odd ones as YAML, so both loaders are exercised.

The directory holds 2,529 APIs in total. **1,008 of them (40%) are Swagger 2.0, which Axon does not read**; they were excluded before sampling. Feeding one to Axon gives a clear `UNSUPPORTED_VERSION` error (tested), not a wrong model.

## Result

| | |
|---|---|
| Parsed | **100 of 100** |
| Operations modelled | 3,795 |
| Median / maximum parse time | 10 ms / 1.9 s |
| Operations touching an **ignored** construct (`partially_analysed`) | 4 (0.1%) |
| Operations touching an **approximated** construct | 274 (7.2%) |

Constructs reported instead of being dropped:

| Construct | Handling | Specs | Occurrences |
|---|---|---|---|
| `oneOf` | approximated as a union | 11 | 57 |
| `anyOf` | approximated as a union | 3 | 35 |
| `discriminator` | mapping not used | 1 | 12 |
| `callbacks` | ignored | 1 | 4 |
| `links` | ignored | 2 | 3 |
| `webhooks` | ignored | 1 | 1 |

## How much this result is worth

- **"Parsed" is a weak claim.** It means no crash and a model was produced. As an independent check, a separate Python script counted operations and component schemas straight from each raw document: **all 100 agree** with Axon's model on both counts. That checks structure, not the correctness of every resolved schema; schema-level correctness is tested by unit tests and, from M2, by the mutation experiment (E1a).
- **The sample is easier than the wild.** APIs.guru normalises what it publishes, so dangling references and invalid YAML are rarer than in specs taken straight from vendors' repositories. A 100% parse rate here should not be read as a 100% rate in general.
- **7% of operations are approximated.** `oneOf`/`anyOf` are modelled as the union of their variants: a field counts as required only if every variant requires it, and which variant applies is not tracked. Diff results on those operations can miss a change that only matters within one variant. Every affected operation is flagged.
- **Not modelled at all, by design, and not listed per occurrence:** value constraints (`minLength`, `maximum`, `pattern`, ...), `default`, `format` changes, response headers, `security`. A diff will not report changes to these.

## Memory (Render free tier is 512 MB)

Measured with `-Xmx` caps on the largest public specs I could find. "Peak" is the sum of per-pool heap peaks, an upper bound.

| Spec | Size | Operations | Retained model | 256 MB heap | 384 MB heap |
|---|---|---|---|---|---|
| GitHub REST (JSON) | 13.0 MB | 1,232 | 8.5 MB | ok, 0.7 s | ok |
| Stripe (JSON) | 8.3 MB | 612 | 17.6 MB | ok, 1.4 s | ok |
| Microsoft Graph v1.0 (YAML) | 25.8 MB | 11,422 | 57 MB | ok, 3.4 s | ok |
| Microsoft Graph beta (YAML) | 58.4 MB | 22,361 | 113 MB | **out of memory** | **out of memory** |

Two things the first measurement exposed, both fixed before these numbers:

1. **The explorer output, not the model, was the problem.** Listing every field of every operation in one document produced 55 MB of JSON for Stripe and 200 MB for Microsoft Graph, and ran out of memory at 384 MB. The explorer is now a spec summary plus per-operation detail on request; the largest single-operation detail is 172 KB.
2. **YAML loading held three copies of the document** (parser nodes, Java maps, JSON tree) and peaked near 1 GB on the 25.8 MB file. The loader now builds the JSON tree directly from parser events; the same file now fits in 256 MB.

Consequence: **uploads are capped at 32 MB** (`SpecParser.MAX_BYTES`), just above the largest spec verified to work in a 256 MB heap. The 58 MB spec is rejected with a `TOO_LARGE` error rather than crashing the server. The cap can be raised with `-Daxon.maxSpecBytes` for local CLI use.
