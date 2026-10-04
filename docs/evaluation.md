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

Run on 2026-10-04. Raw files are under [`eval/results/`](../eval/results); every number below can be traced to one. E4 belongs to M5 and has not run.

| Experiment | Status | One-line result |
|---|---|---|
| E1a mutation | run | Axon reports all 854 of 854 mutations with the expected kind and class; this checks detection, since the expected classes are Axon's own rules |
| E1b oasdiff agreement | run, with a post-hoc rerun | Agrees with `oasdiff` on 84% of the breaking changes of kinds Axon models, 73% of all; first measurement on Stripe was much worse and exposed a bug |
| E1c hand-labelled | **not run** | Sample of 100 drawn; waiting for labels |
| E2 learning curve | run | Field recall 0.93 at 100 requests, 0.998 at 100,000; follows the predicted curve |
| E2 drift detection | run | Undocumented fields and statuses found by 10,000 requests; rare type mismatches need more |
| E3 impact accuracy | run | Ranking tau 0.69 against 0.48 (volume), -0.03 (spec-only), 0.00 (random) |
| E4 ingestion and replay | not run | M5 |

### Three things the evaluation broke

Stated first because they change how much the rest is worth.

1. **The diff hung on Stripe.** E1b's first Stripe pair ran for minutes: schema pairs inside a reference cycle were never cached, so the comparison was exponential. Rewritten to compare each pair of schemas once; a Stripe pair now takes about a second.
2. **The first Stripe measurement was poor, and it was Axon's fault.** Recall against `oasdiff` was 0.22. Changes are reported under field paths, and the walk that places them ran out of budget in Stripe's very connected schema graph before reaching changes that were there. Fixed in three steps (breaking changes before safe ones; breadth-first; then a first pass that visits every changed schema once per operation). These fixes were made after seeing test data, so the numbers after them are **post-hoc**. The as-measured files are kept in `eval/results/e1/as-measured/`.
3. **Two runs of the same seeds disagreed.** E2 and E3 run worlds in parallel over shared spec objects, and a cycle guard in `Schema` was a per-object counter, so one thread could make another see an empty schema. Fixed (per-thread guard, with a concurrency test); two runs are now byte-identical. All E2/E3 numbers below are from after the fix.

A fourth, smaller one: a build without `clean` could ship stale core classes in the shaded jar. Every experiment was rerun from a clean build after it was found.

### E1a: mutation test

854 mutations of 14 kinds applied to the 100 sampled specs (a spec with no eligible site for a kind is skipped). Each mutation is applied to the raw document with the target schema copied inline, so exactly one operation should change.

| Mutation | Expected class | Instances | Axon: found with expected class | oasdiff: found | oasdiff: rated breaking | Text diff: says breaking |
|---|---|---|---|---|---|---|
| Remove a response field | breaking | 89 | 89 | 87 of 87 | 14 | 89 |
| Remove a request field | breaking | 67 | 67 | 65 of 65 | 65 | 67 |
| Add a required request field | breaking | 67 | 67 | 65 of 65 | 65 | 67 |
| Change a request field type | breaking | 56 | 56 | 55 of 55 | 55 | 56 |
| Change a response field type | breaking | 50 | 50 | 48 of 48 | 46 | 50 |
| Narrow a request enum | breaking | 21 | 21 | 20 of 20 | 20 | 21 |
| Widen a request enum | safe | 31 | 31 | 30 of 30 | 0 | 31 |
| Widen a response enum | breaking | 35 | 35 | 33 of 33 | 33 | 35 |
| Remove a status code | breaking | 70 | 70 | 67 of 69 | 19 | 70 |
| Remove an operation | breaking | 99 | 99 | 97 of 97 | 97 | 99 |
| Make a request field required | breaking | 64 | 64 | 62 of 62 | 62 | 64 |
| Make a request field optional | safe | 49 | 49 | 48 of 48 | 0 | 49 |
| Add an optional request field | safe | 67 | 67 | 65 of 65 | 0 | 40 |
| Add a response field | safe | 89 | 89 | 87 of 87 | 0 | 76 |

Breaking label over all mutations (a tool "says breaking" if it reports any breaking change):

| | Precision | Recall | Accuracy |
|---|---|---|---|
| Axon | 1.00 | 1.00 | 1.00 |
| oasdiff | 1.00 | 0.79 | 0.85 |
| Text diff ("a line was removed") | 0.76 | 1.00 | 0.77 |

How to read this:

- **Axon found every mutation (854 of 854).** As pre-registered, a miss would have been a bug. This is a test of *detection*: the "expected class" column is Axon's own rule table, so scoring 1.00 on classification against it is circular and proves nothing about whether the rules are right. E1c is the check on the rules.
- **oasdiff's lower recall is a rule difference, not a defect.** It detects the removed response fields and status codes; it rates an optional field's removal and a non-success status's removal as informational. It failed to load two of the 100 specs (one uses percent-encoded references into `#/paths`), which is why its instance counts are lower.
- **5 Axon instances reported more than the one expected change.** All are in one spec (enode.io) whose operations reference schemas inside *other operations*; editing one operation there really does change the others, so the extra reports are correct.
- The text-diff baseline calls 196 safe changes breaking: it cannot tell an added optional field from a removed one once the surrounding lines shift.
- Not covered: mutations inside `allOf`/`oneOf`/`anyOf`. The mutator only edits plain objects, to keep the ground truth unambiguous. E1b shows that composition is exactly where Axon is weakest.


### E1b: agreement with `oasdiff` on real version histories

200 consecutive version pairs, 50 per source; `oasdiff` 1.33.0. Three APIs.guru pairs are excluded because `oasdiff` refuses them ("duplicate endpoint"). In 116 of the remaining 197 pairs neither tool reports a breaking change. Comparison is on keys `(operation, change kind, response status)`; "breaking" for `oasdiff` means error or warning level.

| Source | Agree | Axon only | oasdiff only | Precision | Recall | Recall on kinds Axon models | Axon / oasdiff time |
|---|---|---|---|---|---|---|---|
| Twilio | 105 | 8 | 115 | 0.93 | 0.48 | 1.00 | 3 s / 33 s |
| APIs.guru | 1,993 | 1,002 | 206 | 0.67 | 0.91 | 0.98 | 1 s / 22 s |
| GitHub | 63 | 19 | 42 | 0.77 | 0.60 | 0.90 | 11 s / 145 s |
| Stripe | 2,547 | 837 | 1,364 | 0.75 | 0.65 | 0.76 | 48 s / 125 s |
| **All** | **4,708** | **1,866** | **1,727** | **0.72** | **0.73** | **0.84** | |

Jaccard overall: 0.57. "Precision" and "recall" here measure agreement with `oasdiff`, not correctness; `oasdiff` is not ground truth.

**Stripe as first measured, before the post-hoc fixes:** precision 0.51, recall 0.22, recall on modelled kinds 0.26. The other three sources did not change with the fixes, except GitHub's recall on modelled kinds (0.69 to 0.90), which moved because of a correction to the comparison itself: `oasdiff` words some `format` changes differently and 22 of them were being counted as type changes.

**Where the tools disagree** (every class was looked at; with thousands of instances the classes were inspected by example, not instance by instance, which is weaker than the "every disagreement" the method promised):

| Class | Keys | Verdict |
|---|---|---|
| Axon: optional response field removed is breaking; `oasdiff`: informational | 1,709 | **Rule difference, both defensible.** By the contract, a client must already cope with an optional field being absent. In practice clients that read it break. Axon keeps it breaking and lets traffic decide how much it matters; the row says whether the field was required. |
| Axon: non-success status removed is breaking; `oasdiff`: informational | 118 | Rule difference. `oasdiff`'s reading is the more reasonable one; Axon's rule is too strict here. |
| `oasdiff`: `format` changed | 137 | **Not modelled by Axon.** A real gap: `int32` to `int64` can break a typed client. |
| `oasdiff`: `anyOf`/`oneOf`/`allOf` list changed | 637 | Not modelled as such. Axon sees the fields that result, not the change to the list. |
| `oasdiff`: enum value added, Axon silent (Stripe) | 826 | **Axon is wrong.** 819 of these are one edit in one version pair. The property sits in the sixth variant of an `anyOf`; where several variants share a property name, Axon's union approximation compares only the first. The operation is flagged `approximated`, but the change is missed. |
| `oasdiff`: type changed from "any" to a type, where the type was already implied through `allOf` | 48 | Axon is right on the ones inspected: it resolves `allOf` before comparing, so nothing changed. |
| `oasdiff`: pattern, length, range or security changed | 72 | Not modelled by Axon, by design. |
| Axon: field removed or made required, `oasdiff` reports it as a different rule on the same operation | 46 | Same change, described differently (for example "new required property" against "became required" after a `oneOf` was removed). |

**What this says.** On diffing alone Axon is not better than `oasdiff`; it models fewer things (`format`, constraints, composition lists) and has one confirmed blind spot (shared property names across union variants). It is faster on these specs and reads one spec `oasdiff` cannot load. The claim that survives is the one made in M0: the contribution is the usage-aware ranking, not the diff.

### E1c: hand-labelled real sample

**Not run.** [`eval/e1c/sample.csv`](../eval/e1c/sample.csv) holds 100 real changes (25 per source, fixed seed, one row per distinct change) with an empty `label` column. The tools' verdicts are in `eval/e1c/verdicts.json`, which must not be opened before labelling. After labelling, `python eval/scripts/e1c_sample.py score` prints each tool's accuracy. The labels have to come from the project author; a tool author's assistant labelling its own tool's output would not be a check.

One bias to state in advance: the sample is drawn from changes that at least one tool calls breaking, so it cannot measure changes both tools miss.

### E2: inference and drift

Test seeds 101-200: 100 worlds, 374 operation runs, six specs (the demo spec and five public ones, listed in the result file). Dev seeds 1-20 gave the same picture.

**Generator self-check.** 6,743 field paths: the measured presence rate after 100,000 requests is within five standard errors of the analytic rate for all of them (largest absolute error 0.0046). The truth the rest is scored against is sound.

**Learning curve** (field recall; precision is 1.00 by construction, since a field that was observed exists):

| Requests per operation | All fields | 10th-90th percentile across worlds | Fields with p < 0.01 | predicted | p 0.01-0.1 | p >= 0.1 | Type accuracy | Status-code recall |
|---|---|---|---|---|---|---|---|---|
| 100 | 0.928 | 0.890-0.994 | 0.278 | 0.274 | 0.932 | 1.000 | 0.992 | 0.849 |
| 1,000 | 0.983 | 0.969-1.000 | 0.805 | 0.810 | 1.000 | 1.000 | 0.998 | 0.987 |
| 10,000 | 0.996 | 0.993-1.000 | 0.950 | 0.953 | 1.000 | 1.000 | 0.999 | 1.000 |
| 100,000 | 0.998 | 0.999-1.000 | 0.981 | 0.983 | 1.000 | 1.000 | 1.000 | 1.000 |

The pre-registered prediction, recall `= 1 - (1 - p)^n`, matches the measurement to within 0.01 in every stratum. That is the expected outcome when the generator draws fields independently, which it does, so the agreement mostly confirms the arithmetic. It would not hold as well on real payloads with correlated fields.

**Drift detection** (found / injected, false positives):

| Requests | Undocumented field | Type mismatch | Undocumented status | Unused field (default: parent seen 300 times) | Unused field with no minimum |
|---|---|---|---|---|---|
| 100 | 291 / 332, 0 | 107 / 197, 0 | 105 / 190, 0 | 0 / 5,062, 0 | 4,821 / 5,062, **370** |
| 1,000 | 331 / 332, 0 | 167 / 197, 0 | 182 / 190, 0 | 2,367 / 5,062, 24 | 5,004 / 5,062, 113 |
| 10,000 | 332 / 332, 0 | 184 / 197, 0 | 190 / 190, 0 | 4,469 / 5,062, 1 | 5,043 / 5,062, 31 |
| 100,000 | 332 / 332, 0 | 193 / 197, 0 | 190 / 190, 0 | 4,906 / 5,062, 2 | 5,054 / 5,062, 13 |

- The minimum-requests rule for "unused" does what it is for: without it, 370 fields that do occur are called unused at 100 requests; with it, none. The price is that nothing is called unused until enough traffic exists.
- Four injected type mismatches are still unseen at 100,000 requests.
- The 5xx rule: 78 undocumented 5xx findings, all informational under the default and all warnings if the rule were switched off. It changes labels, not detection.
- Undocumented operations (measured in E3's worlds, 7-day window): found in 51 of 51 worlds where injected; 50 of 51 with a 1-day window.

**Calibration of the "never seen" bound.** At 1,000 requests Axon made 2,066 statements of the form "never seen, so its rate is below *u* with 95% confidence"; 99.95% are true. That figure flatters the bound, because most of those fields truly never occur. The guarantee itself is about fields that do occur: of 6,486 occurring fields that could have been wrongly called unused, 1 was (0.02%), against the 5% the bound allows. The bound holds and is very conservative on this data. With only 24 statements about fields that do occur, this is weak evidence about the bound's tightness and good evidence that it is not violated.

**Confidence tier.** The pre-registered rule put `high` at the smallest request count where recall reaches 0.99 for fields with p >= 0.01. Pooled recall for those fields is 0.992 at 100 requests on the dev seeds (0.991 on test), the smallest count measured. So the rule yields "high from 100 requests", which says almost nothing. The rule was too lenient; it is applied as written and flagged in [metrics.md](metrics.md).

**Real service (T-demo, 10,958 captured requests).** All six differences built into the service were found. Axon also reported three true things I had not planned: `author`, `author_email` and `cursor` are documented and never used by any client. No false finding. One service, written by me.

### E3: impact accuracy

Test seeds 101-200: 100 worlds, 1,546 breaking changes, 1,445 with a defined truth (status-code and media-type changes have none). Truth is the profile truth: a client counts as affected if its profile can produce the behaviour, whether or not it did so in the window. 98 worlds have enough rows to rank.

**Ranking, 7-day window** (mean over worlds; 10th-90th percentile in brackets):

| Ranking | Kendall tau with true affected-client count | Top-5 precision |
|---|---|---|
| **Axon (as shipped: observed rows first)** | **0.69** [0.41, 0.92] | **0.88** |
| Axon, severity first (the M0 ordering) | 0.71 [0.44, 0.92] | 0.84 |
| Sort by number of clients listed | 0.57 [0.14, 0.93] | 0.72 |
| Volume only (requests) | 0.48 [0.06, 0.84] | 0.68 |
| Spec only (operations touched) | -0.03 [-0.35, 0.29] | 0.39 |
| Random (1,000 shuffles) | 0.00 | 0.42 |

- **The main claim holds.** Usage-aware ranking is far closer to the truth than spec-only ranking, which is no better than random, and clearly better than ranking by volume. The claim "volume is secondary" survives its test.
- **A dev-seed tuning did not hold up.** On dev seeds, putting observed rows first beat severity-first (tau 0.74 against 0.65), so the ordering was changed before the test run. On test seeds the two are the same within noise on tau (0.69 against 0.71) and observed-first is slightly ahead on top-5 (0.88 against 0.84). On the held-out heavy tail severity-first is ahead on tau (0.70 against 0.63) and level on top-5 (0.84). Twenty dev worlds were too few to choose between them. The shipped ordering stays, because re-choosing on test data is what the rules forbid; **which ordering is better is open.**
- On the real demo service (17 changes): Axon tau 0.63, volume 0.21, spec-only -0.13; top-5 precision 1.0, 0.2 and 0.0. One small dataset written by me.

**How much of the affected set Axon sees** (observed rows):

| Window | Population seen | Precision of client set | Recall of client set | Mean error in affected share (90th pct) |
|---|---|---|---|---|
| 1 day | 73% | 1.00 | 0.34 | 0.18 (0.37) |
| 7 days | 89% | 1.00 | 0.69 | 0.08 (0.18) |
| 30 days | 96% | 1.00 | 0.85 | 0.04 (0.08) |
| 30 days, heavy tail | 85% | 1.00 | 0.51 | 0.14 (0.31) |

- **Axon never names a client that is not affected** (precision 1.00), and that is by construction.
- **It misses many that are.** Even with 30 days and 96% of the population seen, 15% of truly affected clients are missing: they were seen, but did not happen to show the affected behaviour in the window. With a heavy tail half are missing. A report that says "3 clients affected" means "at least 3". The `min_detectable_rate_per_day` line in every report says this, and E3 shows it is not a formality.
- Severity computed from Axon's set agrees with severity computed from the true set in 87% of observed rows at 7 days; every disagreement is Axon rating **lower** than the truth (HIGH where the truth is CRITICAL), because of the clients it could not see.

**The cost of "potential"** (response-side rows):

| | 7-day window |
|---|---|
| Clients listed as potentially affected that truly read the field | **25%** (17% on the demo service) |
| True readers that appear in the list | 66% (83% at 30 days) |
| Potential rows whose severity matches the severity of the true readers | 42% |

Three of four clients on a potential row are not affected, and the row's severity is more often wrong than right, nearly always too high (267 rows rated HIGH have no reader at all). The label "potential" is doing real work; a potential CRITICAL should be read as "many callers", not "many broken clients". Finding out who reads a response field needs something traffic does not contain.

### What I would say in an interview

- The diff is a competent subset of `oasdiff`, not a rival to it.
- The ranking claim is supported on synthetic populations and on one small real capture, against four baselines, with the losses shown.
- The tool's counts are lower bounds, and the experiments say by how much.
- Everything synthetic rests on a generator I wrote; the independent evidence is E1b (real specs, real tool) and, once labelled, E1c.

## Amendment log

| Date | Change | Reason | Commit |
|---|---|---|---|
| 2026-10-03 | Initial version | M0 | `4dcbf4e` |
| 2026-10-04 | E1a: four safe mutation kinds added (widen request enum, make a request field optional, add an optional request field, add a response field) and response-side type and enum mutations. | The ten listed kinds were nearly all breaking, which cannot measure false alarms. Added before the first run. | `e92d531` |
| 2026-10-04 | E1b: comparison unit is `(operation, change kind, response status)`, not `(operation, location, kind)`. Disagreements were inspected by class with examples, not one by one. | `oasdiff` reports locations only inside message text; field-level matching was unreliable. Thousands of disagreements made per-instance inspection impractical. Both weaken the method as written. | `e92d531` |
| 2026-10-04 | E1b: histories are 51 consecutive commits at a seeded offset per source (50 consecutive pairs), not 50 independently sampled pairs. | Halves the downloads of 8-13 MB spec files. | `e92d531` |
| 2026-10-04 | E1b normalisation: `oasdiff` "type changed" entries that are only `format` changes are counted as not modelled by Axon. | They were being filed as type changes, mislabelling the gap. A fix to the measurement, found on Twilio and GitHub results. | `52dfd10`, `6da5865` |
| 2026-10-04 | **E1b post-hoc rerun.** Three fixes to how Axon places changes under paths were made after seeing Stripe results. Both the as-measured and the post-hoc numbers are published. | Truncation and path enumeration hid changes Axon had found. | `6da5865` |
| 2026-10-04 | E1c: sample drawn from changes at least one tool calls breaking, 25 per source, one row per distinct change. Labels pending. | The method did not say how the 100 were to be drawn. | `ade12aa` |
| 2026-10-04 | E2: 30 days and six specs; up to six operations per world; requests generated per operation. | Makes 100,000 requests per operation affordable. Written in eval/generator.md before the test run. | `a8323aa` |
| 2026-10-04 | E3: 20 edits per world made by the E1a mutator; windows are the last 1, 7 and 30 days of a 30-day run; two extra comparators (client count, severity-first). Share error compares Axon's share of clients seen with the true share of the population. | Details the method left open, fixed before the test run. | `e5f64c2` |
| 2026-10-04 | E3: ranking order changed on dev seeds (observed first). Not confirmed on test. | See metrics.md amendment log and the E3 results. | `e9e4276` |
