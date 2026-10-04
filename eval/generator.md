# Traffic generator parameters (dataset T-sim)

Written before the first test-seed run, as [docs/evaluation.md](../docs/evaluation.md) requires. Code: `axon-eval/src/main/java/.../eval/sim/` (`World`, `BodyModel`). Dev seeds 1-20 are for looking at; test seeds start at 101 and are run once.

A "world" is built from one OpenAPI spec and one seed. All draws below come from a `java.util.Random` seeded from the world seed, so a world is reproducible from `(spec, seed)`.

## Population

| Parameter | Standard | Held-out heavy tail |
|---|---|---|
| Clients | 100-300 | 150-300 |
| Cohorts | 4-8 | 3-6 |
| Zipf exponent of client activity | 0.8-1.4 | 1.6-2.2 |
| Share of rare clients | 10-20% | 35-50% |
| Total requests per day (regular clients) | 800-2,000 | 800-2,000 |

Regular client *i* calls at a rate proportional to `i^(-exponent)`. A rare client calls between 0.01 and 0.2 times per day (log-uniform). Each client's calls in a window are a Poisson draw at its rate, at uniform times. The heavy-tail parameterisation is never used for development; it exists to check that conclusions do not depend on the standard one.

## Cohorts (client behaviour)

A cohort fixes, for all its clients:

- **Operations used:** each operation with probability 0.5 (at least one), with weight 0.2-2.2.
- **Request fields:** required fields always. An optional field is sent by half the cohorts; of those, 70% always send it and 30% send it with a probability in 0.05-0.9. `readOnly` fields are never sent.
- **Enum values:** for each enum field sent, 60% of cohorts always send one fixed value; the rest pick uniformly.
- **Numbers:** per number field, a coin decides whether the cohort sends fractional or whole values.
- **Query parameters:** required ones always; an optional one is sent by 40% of cohorts (70% of those always, the rest with probability 0.05-0.9).
- **Response fields read:** each field of the success response is read with a per-cohort probability drawn from 0.05-0.5. This is the hidden truth behind "potentially affected".

## Server (response behaviour and injected drift)

| Behaviour | Rule |
|---|---|
| Optional response field | 50% always present; 30% present with probability 0.1-0.9; 15% with probability 0.001-0.1 (log-uniform); **5% never present** (documented but unused) |
| Required response field | present, except **3% never present** |
| Wrong type | 5% of scalar fields are sent with another type at a rate of 0.5, 0.05 or 0.005 |
| Undocumented response field | 30% of response objects get one, present at 0.5, 0.05 or 0.005 |
| Nullable field | null in 10% of occurrences |
| Arrays | 1-3 items; nothing is generated below depth 4 |
| Documented error status | 75% occur, each at 0.5-5% of calls; 25% never occur |
| Undocumented 4xx | 30% of operations, at 2% or 0.2% |
| Undocumented 5xx | 20% of operations, at 1% |
| Undocumented request field | 20% of cohorts send `x_client_extra` |
| Undocumented query parameter | 10% of cohorts send `x_debug` on about half their operations |
| Undocumented operation | in half the worlds, 0.2% of requests go to `/internal/metrics` |

## Ground truth

- **Field presence** is computed analytically from the probabilities above (`BodyModel.rates`), not estimated from samples. E2 checks the analytic rates against a large sample as a self-test of the generator.
- **Who is affected by a change** is derived from the cohort profiles: a client is truly affected if its profile gives the affected behaviour a non-zero probability, whether or not it happened to occur in the window Axon saw. Rare clients are therefore part of the truth even when invisible.

## Specs

The generator runs over the demo spec and five public specs picked by a fixed rule from the M1 sample (first five, in sample order, with 5-60 operations, at least three JSON request bodies of three or more properties, no operation flagged approximated or partially analysed, and no path template containing `#` or `?`, which some AWS-style specs use and which cannot be told apart from a URL fragment or query in traffic). The chosen files are listed in each result file. World `seed` uses spec number `seed mod 6`.

## Known unrealism

- Field presences are independent of each other. Real payloads have correlated fields (a field present only when another is set), which makes real learning curves slower than these.
- Cohort behaviour is fixed over time. Real clients upgrade.
- Calls are Poisson. Real clients include cron jobs and bursts.
- A cohort's reads are independent draws per field. Real clients read related groups of fields.
- All of the above are assumptions I chose. A result on T-sim shows Axon is consistent with them, nothing more; T-demo is the (small) counterweight.
