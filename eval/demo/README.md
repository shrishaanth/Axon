# The demo service (dataset T-demo)

A small recipes API, thirteen client programs, and a real capture of their traffic through mitmproxy. It exists so that Axon's claims can be checked against ground truth on real HTTP, not only on generated events.

| File | What |
|---|---|
| `recipes-v1.yaml` | The documented contract. The service implements it, with the deliberate differences listed below. |
| `recipes-v2.yaml` | A proposed next version: 24 changes, 17 breaking. |
| `usage.jsonl` | 10,958 sanitised events from the capture. Shapes only; no values. |
| `truth.json` | What each client really sent and which response fields its code read. Written by the clients; never given to Axon. |
| `report.json`, `report.txt` | Output of `axon impact` on the above. |

Code: `axon-eval/src/main/java/.../eval/demo/` (`DemoService`, `DemoClients`).

## What is real and what is not

- **Real:** the HTTP requests and responses, the proxy capture (mitmproxy 12.2.3, HAR export), the HAR → sanitiser → impact pipeline.
- **Simulated:** the timestamps. The capture took 40 seconds; the clients stamp each request with a simulated time in an `X-Demo-Time` header covering 60 days ending 2026-09-30, and the sanitiser is told to use it (`--time-header`).
- **Written by the project author:** the service, the clients and the spec. This is a controlled check, not independent evidence of real-world behaviour.

The raw HAR (50 MB) contains request values and is not committed. `usage.jsonl` is its sanitised form.

## Known drift built into the service

| Difference from `recipes-v1.yaml` | Expected finding |
|---|---|
| Every recipe carries `internal_score` | `undocumented_field` |
| `subtitle` is documented but never returned | `unused_field` |
| `servings` is a string for every tenth recipe id | `type_mismatch` |
| `POST /recipes` answers 429 on every 40th call | `undocumented_status` |
| `GET /healthz` exists | `undocumented_operation` |
| `GET /recipes` accepts `debug` | `undocumented_field` (query) |

## The clients

| Client | Active (days ago) | Behaviour that matters |
|---|---|---|
| web-app, web-app-canary | 59-0, 10-0 | sorts by rating; modern request bodies |
| ios-4.2 | 59-0 | modern |
| ios-3.9 | 59-3 | sends `notes`, never `servings`, sometimes `expert`; reads `legacy_slug` |
| android-5 | 59-0 | reads rating comments |
| android-4 | 59-12 | fractional `minutes`, `expert`; reads `legacy_slug` from the create response |
| partner-acme | 59-0 | bulk export; reads `legacy_slug` |
| partner-globex | 59-0 | sorts by newest; reads `legacy_slug` |
| cron-nightly | 59-0 | bulk export and `/healthz` once a day |
| admin-tool | every 6 days | `PUT` with `notes`, `DELETE` |
| importer-script | 3 bursts, last 41 days ago | `notes`, fractional `minutes`, `expert`, no `servings` |
| analytics-bot | 59-0 | highest volume; sends `debug`, reads `internal_score` |
| qa-smoke | once, 30 days ago | calls every read operation; reads nothing |

## Reproduce

```bash
mvn -q -DskipTests clean package
```

```bash
java -jar axon-eval/target/axon-eval-0.1.0-SNAPSHOT-all.jar demo-service 8087
```

```bash
mitmdump --mode regular -p 8089 -w flows.mitm -q
```

```bash
java -jar axon-eval/target/axon-eval-0.1.0-SNAPSHOT-all.jar demo-clients http://127.0.0.1:8087 eval/demo/truth.json 127.0.0.1:8089
```

Stop the proxy, then:

```bash
mitmdump -nr flows.mitm --set hardump=demo.har -q
```

```bash
java -jar axon-cli/target/axon-cli-0.1.0-SNAPSHOT-all.jar sanitise --har demo.har --spec eval/demo/recipes-v1.yaml --identity-header X-Client-Id --salt axon-demo --time-header X-Demo-Time --out eval/demo/usage.jsonl
```

```bash
java -jar axon-cli/target/axon-cli-0.1.0-SNAPSHOT-all.jar impact --baseline eval/demo/recipes-v1.yaml --candidate eval/demo/recipes-v2.yaml --usage eval/demo/usage.jsonl --identity-header X-Client-Id --out eval/demo/report.json
```

```bash
python eval/scripts/check_demo_truth.py
```
