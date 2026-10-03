"""Compare the demo impact report with what the demo clients recorded about themselves.

Independent of Axon's code: it reads eval/demo/truth.json (written by the clients) and eval/demo/report.json
(written by `axon impact`) and derives the truly affected clients for each breaking change in Python.

Usage: python eval/scripts/check_demo_truth.py [--salt axon-demo]
"""
import argparse
import hashlib
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def truly_affected(change, clients):
    op = change["operation"]["method"] + " " + change["operation"]["path"]
    kind = change["kind"]
    loc = change["location"]
    field, status, part = loc.get("field"), loc.get("status"), loc["part"]
    out = set()
    for name, ops in clients.items():
        t = ops.get(op)
        if not t or t["calls"] == 0:
            continue
        if kind == "operation_removed":
            out.add(name)
        elif kind == "request_field_removed":
            key = "?" + field[2:] if part == "request.query" else field
            if t["sent"].get(key, 0) > 0:
                out.add(name)
        elif kind in ("request_field_made_required", "request_field_added_required"):
            if t["bodies"] - t["sent"].get(field, 0) > 0:
                out.add(name)
        elif kind == "request_field_type_changed":
            accepted = set(change["to"].split("|"))
            if "number" in accepted:
                accepted.add("integer")
            if any(ty not in accepted for ty in t["types"].get(field, {})):
                out.add(name)
        elif kind == "request_enum_narrowed":
            removed = set(json.loads(change["from"])) - set(json.loads(change["to"]))
            if any(v in removed for v in t["values"].get(field, {})):
                out.add(name)
        elif kind.startswith("response"):
            if field in t["reads"].get(status, []):
                out.add(name)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--salt", default="axon-demo")
    args = ap.parse_args()
    clients = json.load(open(os.path.join(ROOT, "eval", "demo", "truth.json"), encoding="utf-8"))["clients"]
    report = json.load(open(os.path.join(ROOT, "eval", "demo", "report.json"), encoding="utf-8"))
    names = {hashlib.sha256((args.salt + n).encode()).hexdigest()[:16]: n for n in clients}

    observed_exact = observed_total = 0
    potential_supersets = potential_total = 0
    callers = readers = 0
    for c in sorted((c for c in report["changes"] if c["breaking"]), key=lambda c: c["rank"]):
        block = c.get("observed_affected") or c.get("potentially_affected")
        got = {names[x["client"]] for x in block["top_clients"]}
        assert block["clients"] == len(got), "more than ten affected clients: top_clients is not the full set"
        truth = truly_affected(c, clients)
        if c["evidence"] == "observed":
            observed_total += 1
            observed_exact += got == truth
            verdict = "exact" if got == truth else "WRONG"
        else:
            potential_total += 1
            potential_supersets += truth <= got
            callers += len(got)
            readers += len(truth)
            verdict = "superset" if truth <= got else "MISSED A READER"
        print(f"{c['rank']:2d} {c['evidence']:9s} {verdict:9s} axon={len(got)} truth={len(truth)}  {c['description']}")
    print()
    print(f"observed rows exactly right: {observed_exact} of {observed_total}")
    print(f"potential rows that contain every true reader: {potential_supersets} of {potential_total}")
    print(f"potential rows list {callers} client-change pairs; {readers} of them truly read the field "
          f"({readers / callers:.0%})")
    if observed_exact != observed_total or potential_supersets != potential_total:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
