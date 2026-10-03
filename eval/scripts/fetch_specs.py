"""Fetch the public spec sample (dataset S-pub, single-version part).

Draws a fixed-seed sample of OpenAPI 3.x documents from the APIs.guru directory and downloads them into
eval/data/s-pub/ (not committed; the sample list and checksums are committed as eval/datasets/s-pub.json so the
set can be rebuilt). Even sample positions are fetched as JSON and odd ones as YAML, to exercise both loaders.

Usage: python eval/scripts/fetch_specs.py [--n 100] [--seed 20261003]
"""
import argparse
import hashlib
import json
import os
import random
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIST_URL = "https://api.apis.guru/v2/list.json"
# Not part of the random sample: fetched for the M1 memory measurement.
LARGE = {
    "large/github.json": "https://raw.githubusercontent.com/github/rest-api-description/main/descriptions/api.github.com/api.github.com.json",
    "large/stripe.json": "https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json",
    "large/msgraph.yaml": "https://api.apis.guru/v2/specs/microsoft.com/graph/1.0.1/openapi.yaml",
    "large/msgraph-beta.yaml": "https://api.apis.guru/v2/specs/microsoft.com/graph-beta/1.0.1/openapi.yaml",
}


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "axon-eval"})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n", type=int, default=100)
    ap.add_argument("--seed", type=int, default=20261003)
    args = ap.parse_args()

    data_dir = os.path.join(ROOT, "eval", "data")
    out_dir = os.path.join(data_dir, "s-pub")
    os.makedirs(os.path.join(out_dir, "large"), exist_ok=True)
    list_path = os.path.join(data_dir, "apis-guru-list.json")
    if not os.path.exists(list_path):
        with open(list_path, "wb") as f:
            f.write(get(LIST_URL))
    with open(list_path, encoding="utf-8") as f:
        directory = json.load(f)

    by_version = {}
    candidates = []
    for name in sorted(directory):
        entry = directory[name]
        preferred = entry["versions"][entry["preferred"]]
        ver = preferred.get("openapiVer", "?")
        by_version[ver[:3]] = by_version.get(ver[:3], 0) + 1
        if ver.startswith("3."):
            candidates.append((name, entry["preferred"], preferred))

    rng = random.Random(args.seed)
    sample = rng.sample(candidates, args.n)

    manifest = {
        "source": LIST_URL,
        "list_sha256": hashlib.sha256(open(list_path, "rb").read()).hexdigest(),
        "seed": args.seed,
        "directory_size": len(directory),
        "directory_by_declared_version": by_version,
        "eligible_openapi_3": len(candidates),
        "specs": [],
        "large": [],
    }
    for i, (name, version, info) in enumerate(sample):
        as_yaml = i % 2 == 1
        url = info["swaggerYamlUrl"] if as_yaml else info["swaggerUrl"]
        safe = name.replace(":", "__").replace("/", "_")
        rel = f"{i:03d}-{safe}.{'yaml' if as_yaml else 'json'}"
        path = os.path.join(out_dir, rel)
        record = {"index": i, "api": name, "version": version, "declared": info.get("openapiVer"),
                  "url": url, "file": rel}
        try:
            if not os.path.exists(path):
                with open(path, "wb") as f:
                    f.write(get(url))
            body = open(path, "rb").read()
            record["bytes"] = len(body)
            record["sha256"] = hashlib.sha256(body).hexdigest()
        except Exception as e:  # recorded, not hidden: a failed download is part of the survey
            record["download_error"] = str(e)
            if os.path.exists(path):
                os.remove(path)
        manifest["specs"].append(record)
        print(f"{i:3d} {record.get('bytes', 'ERR'):>10} {name}", file=sys.stderr)

    for rel, url in LARGE.items():
        path = os.path.join(out_dir, rel)
        record = {"file": rel, "url": url}
        try:
            if not os.path.exists(path):
                with open(path, "wb") as f:
                    f.write(get(url))
            body = open(path, "rb").read()
            record["bytes"] = len(body)
            record["sha256"] = hashlib.sha256(body).hexdigest()
        except Exception as e:
            record["download_error"] = str(e)
        manifest["large"].append(record)
        print(f"large {record.get('bytes', 'ERR'):>10} {rel}", file=sys.stderr)

    os.makedirs(os.path.join(ROOT, "eval", "datasets"), exist_ok=True)
    with open(os.path.join(ROOT, "eval", "datasets", "s-pub.json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, indent=2)
        f.write("\n")


if __name__ == "__main__":
    main()
