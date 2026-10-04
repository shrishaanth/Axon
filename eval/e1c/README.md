# E1c: the hand-labelled sample

**Status: waiting for labels.** This is the one experiment that needs a person.

`sample.csv` holds 100 real changes between consecutive versions of public API specs: 25 each from APIs.guru, GitHub, Stripe and Twilio, drawn with a fixed seed from the changes that Axon or `oasdiff` (or both) call breaking. Each distinct change appears once, however many operations it shows up in.

## How to label

1. **Do not open `verdicts.json`.** It records which tool flagged each row and how. Looking first defeats the point.
2. For each row, read `operation`, `response_status` and `what_changed`. If that is not enough, open the two spec files named in `base` and `revision` (under `eval/data/histories/` after running `python eval/scripts/fetch_histories.py`), or the vendor's changelog.
3. Fill `label` with `breaking` or `safe`:
   - **breaking**: a client written against the older version, and correct for it, can fail or misbehave against the newer one.
   - **safe**: it cannot.
4. Use `notes` for anything uncertain. An honest "unsure" is better than a guess; leave `label` empty for those and they are left out of the score.
5. Commit the labelled file **before** running the scorer, so the labels are on record first.

```bash
python eval/scripts/e1c_sample.py score
```

## What the score will and will not mean

- It gives each tool's accuracy against your labels on the same 100 rows.
- Every row is one that at least one tool calls breaking, so the sample cannot show changes both tools miss.
- One labeller, 100 rows. Report it as "labelled by the project author", with the number of rows left unlabelled.
- Expect the tools to differ mostly on two rule questions: whether removing an *optional* response field is breaking, and whether a `format` change is. Decide your own rule for those before you start and write it in the first `notes` cell you use it in.
