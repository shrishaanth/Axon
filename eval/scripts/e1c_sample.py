"""Draw the hand-labelling sample for E1c and, later, score the tools against the labels.

  python eval/scripts/e1c_sample.py draw     # writes eval/e1c/sample.csv and eval/e1c/verdicts.json
  python eval/scripts/e1c_sample.py score    # after labelling: accuracy of each tool

The sample is 100 real changes that at least one tool calls breaking, 25 per source, drawn with a fixed seed from
the key dumps written by `axon-eval e1b --dump-keys`. Each row describes the change in neutral words; which tool
reported it and how it was rated is kept in verdicts.json, which the labeller must not open before labelling.

Label column: "breaking" or "safe". Meaning of breaking: a client written against the older version, and
behaving correctly for it, can fail or misbehave against the newer one.
"""
import csv
import glob
import json
import os
import random
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "eval", "e1c")
SEED = 20261003
PER_SOURCE = 25


def neutral(key, row):
    """A description that does not give away the tool's rating."""
    operation, kind, status = [part.strip() for part in key.split("|")]
    text = row.get("axon_text") or row.get("oasdiff_text") or ""
    # oasdiff appends advice to some messages; keep only the factual first sentence
    text = re.split(r"(?<=[.`])\s+(?:This is a warning|The server may|It is recommended)", text)[0]
    return operation, kind, status, text


def draw():
    rng = random.Random(SEED)
    rows = []
    verdicts = {}
    for path in sorted(glob.glob(os.path.join(ROOT, "eval", "data", "e1c", "keys-*.jsonl"))):
        source = os.path.basename(path)[5:-6]
        items = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
        # one row per distinct (kind, description): a shared-schema edit seen from 500 operations is one change
        seen = {}
        for item in items:
            operation, kind, status, text = neutral(item["key"], item)
            signature = (kind, re.sub(r"(GET|PUT|POST|DELETE|PATCH|HEAD|OPTIONS) \S+", "", text))
            seen.setdefault(signature, item)
        distinct = list(seen.values())
        rng.shuffle(distinct)
        for item in distinct[:PER_SOURCE]:
            operation, kind, status, text = neutral(item["key"], item)
            row_id = f"{source}-{len([r for r in rows if r['source'] == source]) + 1:02d}"
            rows.append({"id": row_id, "source": source, "base": item["base"], "revision": item["revision"],
                         "operation": operation, "response_status": status, "what_changed": text,
                         "label": "", "notes": ""})
            verdicts[row_id] = {"kind": kind, "axon_breaking": item["axon_breaking"],
                                "oasdiff_breaking": item["oasdiff_breaking"],
                                "described_by": "axon" if item.get("axon_text") else "oasdiff"}
        print(f"{source}: {len(items)} keys, {len(distinct)} distinct changes, "
              f"{min(PER_SOURCE, len(distinct))} sampled", file=sys.stderr)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "sample.csv"), "w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    with open(os.path.join(OUT, "verdicts.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(verdicts, f, indent=1)
        f.write("\n")
    print(f"{len(rows)} rows written to eval/e1c/sample.csv", file=sys.stderr)


def score():
    verdicts = json.load(open(os.path.join(OUT, "verdicts.json"), encoding="utf-8"))
    labelled = 0
    right = {"axon": 0, "oasdiff": 0}
    agree = 0
    for row in csv.DictReader(open(os.path.join(OUT, "sample.csv"), encoding="utf-8")):
        label = row["label"].strip().lower()
        if label not in ("breaking", "safe"):
            continue
        labelled += 1
        v = verdicts[row["id"]]
        truth = label == "breaking"
        right["axon"] += v["axon_breaking"] == truth
        right["oasdiff"] += v["oasdiff_breaking"] == truth
        agree += v["axon_breaking"] == v["oasdiff_breaking"]
    if labelled == 0:
        print("no labels yet: fill the 'label' column of eval/e1c/sample.csv with breaking or safe")
        return
    print(f"labelled rows: {labelled} of {len(verdicts)}")
    print(f"Axon accuracy:    {right['axon'] / labelled:.3f}")
    print(f"oasdiff accuracy: {right['oasdiff'] / labelled:.3f}")
    print(f"the two tools agree on {agree / labelled:.3f} of the labelled rows")
    print("note: every sampled row is one that at least one tool calls breaking, so a tool that called everything "
          "breaking would score the share of rows labelled breaking; read the numbers against that.")


if __name__ == "__main__":
    (draw if len(sys.argv) < 2 or sys.argv[1] == "draw" else score)()
