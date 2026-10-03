# Axon: evaluation method

Status: **method frozen at M0, before any result exists.** The Results sections below are empty on purpose. Changing the method after seeing results requires an entry in the [amendment log](#amendment-log) that says what changed and why, and affected results are re-run and labelled.

## 0. Rules of the evaluation

1. **Baselines first.** Every claim is compared with a deliberately dumb alternative, listed per experiment.
2. **Unfavourable results are published** in the Results sections, with the same prominence as favourable ones. If Axon only ties `oasdiff` on diffing, that is what the document says; the contribution is then the usage-aware ranking.
3. **Tune on dev seeds/specs, report on test seeds/specs.** Nothing is adjusted after looking at test numbers. If something is, the run is relabelled "post-hoc" and the pre-change number stays.
4. **Every run is recorded**: commit hash, tool versions, seeds, hardware, raw output under `eval/results/`. No number in a README without a pointer to its raw file.
5. **Synthetic traffic proves consistency with my own assumptions, not real-world behaviour.** Results on synthetic data and on the real demo service are reported separately, never pooled.
6. **Author bias is named.** I write the generator, the demo clients and the hand labels. Where that matters, it is stated next to the number.

## 1. Datasets

| Id | What | Used by | Notes |
|---|---|---|---|
| S-pub | Public OpenAPI specs: the APIs.guru directory, and version histories from Stripe (`stripe/openapi`), GitHub (`github/rest-api-description`), Twilio (`twilio/twilio-oai`) | M1 parse survey, E1 | Pinned by commit hash in `eval/datasets.lock`. |
| S-mut | Mutations applied to S-pub specs by a script | E1 | Ground truth is known by construction. |
| T-sim | Output of the spec-driven traffic generator (§2) | E2, E3, E4 | Reproducible from `(spec, seed, config)`. |
| T-demo | Traffic from a small demo service plus client programs I write, captured through mitmproxy | E2, E3 | Real HTTP, but I wrote both ends. |

### 2. Traffic generator (hidden ground truth)

For a given spec, the generator:

1. Builds a **client population** (default 200) in **cohorts** (e.g. SDK versions). Each cohort has a behaviour profile: operations used with weights, request fields sent, **response fields read**, error behaviour.
2. Draws per-client activity from a heavy-tailed distribution (Zipf-like; exponent recorded) so that a few clients dominate volume and many are rare.
3. Samples bodies from the schema; timestamps from a Poisson process per client.
4. **Injects drift at known rates**: undocumented fields, missing documented fields, wrong types, undocumented statuses.
5. Writes `truth.json`: for any change, the exact set of clients affected (sent the field / called the operation / read the field).
6. Can replay events into the ingest endpoint at a chosen rate (E4).

Dev seeds: 1-20. Test seeds: 101-200. Parameters (population, exponent, cohort count) are drawn from fixed ranges per seed, listed in `eval/generator.md` before the first test run.

## E1: Diff correctness

**Question.** Does Axon classify breaking vs non-breaking changes correctly?

**E1a: mutation test (ground truth by construction).** Mutations: remove a response field, remove a request field, add a required request field, change a field type, narrow an enum, widen an enum, remove a status code, remove an operation, make an optional field required, make a required field optional. Applied to 100 specs sampled by seed from S-pub (all that parse). Each mutation has a known expected class. Metric: per-kind recall/precision of the breaking label. **Expectation: 1.00 on supported constructs; any miss is a bug, fixed and logged, not averaged away.**

**E1b: agreement with `oasdiff` on real histories.** Take 200 consecutive version pairs from S-pub, up to 50 per source (Stripe, GitHub, Twilio, APIs.guru), sampled by fixed seed. Run `oasdiff breaking` (version pinned in `eval/datasets.lock`) and Axon. Normalise each reported change to `(operation, location, kind)`. Metrics: precision/recall of Axon's breaking set against `oasdiff`'s, plus the Jaccard index. **Every disagreement is inspected and given a verdict**: Axon right, `oasdiff` right, both defensible (rule difference), or unsupported. The verdict table is published.

**E1c: hand-labelled real sample.** 100 real changes sampled from E1b, labelled breaking/non-breaking by me, using vendor changelogs where they exist and the spec diff otherwise. **Labelled before running either tool on the sample, and the labels file is committed first.** Stated plainly in the README as "labelled by the project author". Metrics: accuracy of Axon, accuracy of `oasdiff`, agreement between them on the same 100.

**Baselines.** `oasdiff` (the incumbent), and a naive text diff of the two specs flagged "breaking" if any line is removed.

**Limits.** `oasdiff` is not ground truth. Agreement shows consistency with the incumbent, not correctness. The hand sample is small and single-labeller.

**Pass/report rule.** No threshold for "good enough". Numbers are reported as measured; the headline is the E1c accuracy and the verdict table.

## E2: Inference and drift

**Question.** How much traffic does it take for the observed contract to be accurate, and does Axon find injected drift?

**Learning curve.** For each test seed, take the first *n* matched requests per operation for *n* in {100, 1,000, 10,000, 100,000} (operations with fewer are excluded from that point and counted). Compare the inferred contract with the hidden true contract:

- **Field precision/recall**, per operation, per location (request/response), averaged over operations, with the 10th-90th percentile band across seeds.
- **Type accuracy**: share of observed fields whose inferred type set equals the truth.
- **Status-code recall.**
- Fields are stratified by true presence rate *p* (<0.01, 0.01-0.1, 0.1-1.0) because rare fields are where the curve bites.

**Pre-registered prediction.** Recall for a field at presence rate *p* should follow `1 - (1 - p)^n` (the formula behind [metrics.md §7](metrics.md)). I will plot predicted against measured. A large gap means the model (independent draws) is wrong, and that is itself a result.

**Confidence calibration.** The `tier` thresholds (metrics.md §7c) are read off this curve and written into the amendment log. Then verified on the test seeds: the claimed 95% bound should hold on about 95% of unseen-field statements. The measured coverage is published.

**Drift detection.** With drift injected at known rates, report per-kind detection rate (recall) and false-positive count versus *n*. Baseline: "any field in traffic not in spec" (no rate, no threshold) to show what thresholds and the 5xx rule buy.

**Real service (T-demo).** Same metrics against the demo service's true contract, reported separately.

## E3: Impact accuracy (the flagship claim)

**Question.** Is usage-aware impact ranking closer to the truth than the spec-only baseline?

**Setup.** On each test seed, generate 20 candidate changes of mixed kinds (observed and potential classes). Truth: the exact affected client set per change from `truth.json`. Axon sees only a window of traffic (windows of 1, 7, 30 days of simulated time, to expose the rare-client effect).

**Metrics.**

- **Share error**: `|estimated_share - true_share|` for the affected-client share, mean and 90th percentile, observed-class changes only.
- **Ranking quality**: Kendall tau between predicted rank and rank by true affected clients; top-5 precision of "most-impacting changes".
- **Over-approximation of potential**: for response-field changes, `true_readers / callers`, i.e. how often the potential set is mostly innocent callers. This quantifies the central limitation.
- **Severity calibration**: confusion matrix of predicted severity vs severity computed from truth.

**Baselines.** (1) Spec-only (metrics.md §8). (2) Volume-only: rank by affected request share. (3) Random order, 1,000 shuffles. The volume-only baseline matters because the design claims "volume is secondary"; E3 tests that claim, and if volume-only ranks as well, it is reported.

**Real service.** Repeated on T-demo, where the client programs have known field reads. Reported separately, with the note that I wrote both sides.

**Threats.** The generator encodes my assumptions about heavy tails and cohorts; an estimator tuned to those looks good on them. Mitigations: dev/test split, a second heavy-tail parameterisation held out entirely, and T-demo.

## E4: Ingestion

**Question.** How fast can one Postgres ingest, and how long does replay take?

**Method.** Single Postgres (version recorded) on the machine listed in the result file; the generator drives the ingest endpoint with batch sizes {1, 100, 1,000}. Metrics: **sustained** events/s over 10 minutes (not peak), p95 request latency, rows/s of the aggregator, peak API process memory, and **replay time** to rebuild all aggregates from N stored events for N in {10^5, 10^6, 10^7}. Replay must reproduce the live aggregates exactly; the test compares them and a mismatch is a bug.

**Baseline.** Row-at-a-time inserts (no batching), to show what batching buys.

**Limits.** A development laptop, not Render's free tier; if the deployed instance is measured, it is a separate row. Not a capacity-planning claim.

## Results

*Empty. Filled in only after the experiments run.*

| Experiment | Status |
|---|---|
| E1a mutation | not run |
| E1b oasdiff agreement | not run |
| E1c hand-labelled | not run |
| E2 learning curve | not run |
| E2 drift detection | not run |
| E3 impact accuracy | not run |
| E4 ingestion and replay | not run |

## Amendment log

| Date | Change | Reason | Commit |
|---|---|---|---|
| 2026-10-03 | Initial version | M0 | (this commit) |
