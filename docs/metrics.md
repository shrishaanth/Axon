# Axon: metric and term definitions

Status: **frozen at M0, before any implementation.** Anything here can change only through an entry in the [amendment log](#amendment-log) at the bottom, with the reason and the commit that made the change. Evaluation results are interpreted against the definitions as they stood when the result was produced.

## 1. Vocabulary

| Term | Definition |
|---|---|
| **Operation** | One `(HTTP method, path template)` pair from the spec, e.g. `GET /users/{id}`. `operationId` is carried when present but never required. |
| **Event** | One observed request/response pair, after sanitising (see [report-schema.md](report-schema.md)). Values are gone; shapes remain. |
| **Match** | Resolving an event's concrete path to an operation. Literal segments win over `{param}` segments (`/users/me` beats `/users/{id}`). An event that matches nothing is *unmatched*, never dropped. |
| **Shape** | The set of `(field path, type)` pairs present in a body or in the query string. Field paths use `$.a.b[].c`; `[]` collapses array indexes. |
| **Observed contract** | Per operation, aggregated from events: field presence rates, type sets, status-code counts, latency percentiles. |
| **Change** | One atomic difference between baseline and candidate spec (field removed, status code removed, enum narrowed, ...). |

## 2. The two evidence classes (never blended)

Traffic shows who **sends** a request field and who **calls** an operation. It does not show who **reads** a response field. So every change carries exactly one evidence label, fixed by the kind of change:

| Change kind | Evidence | What the number means |
|---|---|---|
| Operation removed | `observed` | Clients that called the operation in the window. |
| Request parameter / body field removed | `observed` | Clients that sent the field. |
| Request field becomes required (or required field added) | `observed` | Clients that called the operation **without** the field in some request. |
| Request field type changed or narrowed; request enum narrowed | `observed` | Clients that sent a value/type that the candidate rejects. Enum values are visible only for spec-declared enums (capped, see schema). |
| Response field removed, renamed or type-changed; response enum widened | `potential` | Clients that call the operation. They **may** read the field; traffic cannot say. |
| Response status code removed | `potential` | Clients that call the operation. |
| Added optional request field, added response field, widened request enum | none (safe) | Reported as safe; no impact computed. |

Rules that follow:

1. A report row has either an `observed_affected` block or a `potentially_affected` block, never both, never a sum. The field names differ on purpose so the two cannot be merged by accident.
2. The UI must label potential impacts as such (dashed or lighter marks, the word "potential") and must show this table's rationale one click away.
3. Evaluation (E3) measures how much `potential` over-approximates the true readers. That gap is the cost of the limitation, and it is published.

## 3. Client identity

A **client** is whatever the workspace configures as identity:

| Mode | Source | Notes |
|---|---|---|
| `header` | a named request header (e.g. `X-Client-Id`, `User-Agent`) | Value is salted-hashed in the sanitiser. |
| `api_key_hash` | the `Authorization`/API-key header | Same hashing; the raw key never leaves the sanitiser. |
| `none` | not available | Only volume exists. See §4. |

The hash is `hex(sha256(workspace_salt || value))[0:16]`. It is a stable pseudonym, not anonymisation: whoever holds the salt and a candidate value can confirm a guess. The documentation says so.

Known limits, stated up front: one human behind many keys looks like many clients; NAT or a shared key looks like one. Axon counts identities, not organisations.

## 4. No client identity

If mode is `none`, **clients cannot be counted**. The report then:

- sets `severity` to `UNRATED` (not LOW),
- shows `affected_requests`, `first_seen`, `last_seen`,
- sorts by `last_seen`, then `affected_requests`,
- shows a banner "no client identity configured; impact is volume only".

## 5. Window

The **window** is an explicit, closed interval `[start, end]` stored in the report. Default: the span of the supplied events (`min(ts)` to `max(ts)`), never silently "last 30 days". All recency is measured back from `end`. Window length `w` (days, fractional allowed) feeds confidence (§7).

## 6. Impact metrics and severity

For one change with evidence class *E*, over the window:

- `clients` (*k*): distinct clients in the affected set (per §2).
- `requests`: events in the affected set.
- `last_seen`, `first_seen`: latest/earliest event of any affected client in the affected set.
- `active_clients` (*N*): distinct clients with at least one event, on any operation, within `recent_days` of `end`.
- `k_recent`: affected clients last seen within `recent_days` of `end`.
- `k_stale`: affected clients last seen within `stale_days` of `end` (a superset of `k_recent`).
- `share_recent` = `k_recent / N` (null if `N = 0`).

**Severity** is decided by this ladder, first match wins. Volume plays no part.

| Order | Condition | Severity |
|---|---|---|
| 1 | `k = 0` | `NONE_OBSERVED` |
| 2 | `share_recent >= critical_share` | `CRITICAL` |
| 3 | any `critical_clients` member seen within `stale_days`, or `k_recent >= high_min_clients`, or `share_recent >= high_share` | `HIGH` |
| 4 | `k_recent >= 1` | `MEDIUM` |
| 5 | `k_stale >= 1` | `LOW` |
| 6 | otherwise (affected clients exist, none seen within `stale_days`) | `DORMANT` |

Defaults (all per-workspace configurable, all recorded in the report): `recent_days = 7`, `stale_days = 30`, `high_min_clients = 3`, `high_share = 0.10`, `critical_share = 0.50`, `critical_clients = []`.

Design intent, as testable properties:

- One client seen yesterday is **MEDIUM at minimum**, however small its traffic share. It can only be LOW or DORMANT if it has gone quiet.
- Marking a client critical raises any change touching it to **HIGH** within `stale_days`.
- Severity is monotone: adding an affected client or making one more recent never lowers severity. This is a unit-test property.
- `NONE_OBSERVED` is not "safe". It always ships next to its confidence bound (§7).
- If `w < stale_days`, DORMANT cannot occur and the report notes the window is shorter than `stale_days`.

**Ranking** inside a severity tier: `k_recent` desc, then `last_seen` desc, then `requests` desc, then spec order (so output is deterministic).

Severity applies to both evidence classes with the same ladder, but a `potential` row is displayed as "potential <severity>" and is never counted in the same summary bucket as an `observed` row.

## 7. Confidence

Confidence is derived, not asserted. Two numbers are always reported, plus a tier that stays `uncalibrated` until E2 supplies the mapping.

**(a) Field-level, for statements about the observed contract** ("field never seen", "field present in 3% of requests"). With *n* matched requests for the operation, a field that is truly present at rate *p* is seen at least once with probability `1 - (1 - p)^n`. If it was never seen, the 95% upper bound on its rate is `1 - 0.05^(1/n)` (about `3/n`, the "rule of three"). Reported as `unseen_rate_upper_95`.

**(b) Client-level, for statements about who is affected.** A client calling at a steady rate *r* per day is entirely missed over a window of *w* days with probability `e^(-r*w)`. Clients rarer than `3 / w` calls per day may be missing at more than 5% each. Reported as `min_detectable_rate_per_day = 3 / w`. This assumes Poisson-like arrivals; bursty or periodic clients (a monthly batch job) violate it, and the doc says that wherever this number is shown.

**(c) Tier.** `tier ∈ {uncalibrated, low, medium, high}`. The rule is fixed now and thresholds are filled in from E2, not guessed:

- `high`: the smallest *n* at which, in E2, median field recall is >= 0.99 for fields with *p* >= 0.01,
- `medium`: same with recall >= 0.90,
- `low`: below that.

Until E2 has run, `tier = "uncalibrated"` and only (a) and (b) are shown. Thresholds are inserted via the amendment log with a link to the E2 result.

## 8. Spec-only baseline

What a diff tool gives you without traffic: every breaking change is treated alike. Baseline rank = number of operations touched (desc), then spec order. It exists so E3 can measure whether usage data changes the ranking for the better. The baseline implementation lives in `axon-core` as a ranker with the same interface as the real one.

## 9. Observed contract

Per operation, from matched events in the window:

- **Fields:** for each `(location, field path)`: `count`, `presence_rate = count / n_ops_events` (for the relevant part: request body, or response body of a given status), `types` (set with counts).
- **Status codes:** counts per code.
- **Latency:** log-bucketed histogram (mergeable, so replay and batch agree), reported as p50/p95/p99 with bucket resolution stated. Only when the input has timings (HAR does; plain JSONL may not).
- **Numbers:** JSON has no integer type. A number with no fractional part is `integer`; otherwise `number`. Observed `integer` is compatible with documented `number`; observed fractional `number` against documented `integer` is a mismatch. Observed `null` against a non-nullable documented type is a mismatch.
- Conditional presence (a field present only when another is set) is **not** modelled. Only marginal presence rates are reported. This is a stated limitation.

## 10. Drift

Drift compares the observed contract with the **candidate-independent** current spec (the baseline).

| Kind | Definition | Default severity |
|---|---|---|
| `undocumented_field` | In traffic, absent from the spec. Carries `presence_rate` and *n*. | warning |
| `unused_field` | Documented, never observed. Only reported when *n* >= `unused_min_requests` (default 300, so the 95% bound is <= 1%). Carries `unseen_rate_upper_95`. | info |
| `type_mismatch` | Observed type set not covered by the documented type (rules in §9). | warning |
| `undocumented_status` | Status code seen but not declared and no matching `default`. See server-error rule. | by class below |
| `undocumented_operation` | Unmatched events (method/path match nothing in the spec). Grouped by normalised path. | warning |
| `unused_operation` | Documented, zero events in the window. Carries window length. | info |

**Server-error rule.** An undocumented `5xx` is **informational** by default (servers fail; specs rarely list them). Configurable per API with `server_errors: info | drift`. Undocumented `4xx` and unexpected `2xx` are always real drift findings, as is an undocumented `3xx`.

## 11. Out-of-scope constructs

The explorer and diff parse OpenAPI 3.0 and 3.1 JSON/YAML. A construct that is recognised but not modelled (for example `discriminator` mapping semantics, `callbacks`, `links`, external `$ref` to remote URLs) produces an `unsupported` entry with its location and reason. It is **never silently ignored**, and the diff marks any operation touching it as `partially_analysed`.

## Amendment log

| Date | Change | Reason | Commit |
|---|---|---|---|
| 2026-10-03 | Initial version | M0 | (this commit) |
