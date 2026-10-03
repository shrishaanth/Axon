"""Fetch real spec version pairs for E1b (dataset S-pub, history part).

Sources and how pairs are chosen (seed fixed):
  * stripe, github, twilio: the git history of one spec file. A run of 51 consecutive commits that touched the
    file is taken at a seeded offset, giving 50 consecutive (older, newer) pairs.
  * apisguru: APIs that publish several OpenAPI 3.x versions in the APIs.guru directory. Adjacent versions form a
    pair; 50 pairs are sampled.

Files go to eval/data/histories/ (not committed). The manifest eval/datasets/histories.json is committed.

Usage: python eval/scripts/fetch_histories.py [--pairs 50] [--seed 20261003]
"""
import argparse
import hashlib
import json
import os
import random
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, "eval", "data")
OUT = os.path.join(DATA, "histories")

GIT_SOURCES = {
    "stripe": ("https://github.com/stripe/openapi.git", "openapi/spec3.json"),
    "github": ("https://github.com/github/rest-api-description.git",
               "descriptions/api.github.com/api.github.com.json"),
    "twilio": ("https://github.com/twilio/twilio-oai.git", "spec/json/twilio_api_v2010.json"),
}


def run(args, cwd=None, binary=False):
    r = subprocess.run(args, cwd=cwd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"{' '.join(args)}: {r.stderr.decode(errors='replace')[:400]}")
    return r.stdout if binary else r.stdout.decode()


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def git_source(name, url, path, pairs, rng):
    repo = os.path.join(DATA, "repos", name)
    if not os.path.exists(repo):
        os.makedirs(os.path.dirname(repo), exist_ok=True)
        run(["git", "clone", "--filter=blob:none", "--no-checkout", "--quiet", url, repo])
    commits = run(["git", "log", "--format=%H %cI", "--", path], cwd=repo).split("\n")
    commits = [c.split(" ") for c in commits if c.strip()]
    commits.reverse()  # oldest first
    need = pairs + 1
    start = rng.randrange(0, max(1, len(commits) - need + 1))
    window = commits[start:start + need]
    out_dir = os.path.join(OUT, name)
    os.makedirs(out_dir, exist_ok=True)
    files = []
    for i, (commit, date) in enumerate(window):
        target = os.path.join(out_dir, f"{i:03d}-{commit[:12]}.json")
        if not os.path.exists(target):
            blob = run(["git", "show", f"{commit}:{path}"], cwd=repo, binary=True)
            with open(target, "wb") as f:
                f.write(blob)
        files.append({"file": f"{name}/{os.path.basename(target)}", "commit": commit, "date": date,
                      "sha256": sha(target), "bytes": os.path.getsize(target)})
        print(f"{name} {i:3d} {commit[:12]} {date}", file=sys.stderr)
    result = []
    for i in range(len(files) - 1):
        result.append({"source": name, "base": files[i], "revision": files[i + 1]})
    return {"repo": url, "path": path, "commits_touching_file": len(commits), "window_start": start}, result


def apisguru(pairs, rng):
    with open(os.path.join(DATA, "apis-guru-list.json"), encoding="utf-8") as f:
        directory = json.load(f)
    candidates = []
    for name in sorted(directory):
        versions = directory[name]["versions"]
        ordered = sorted((v for v in versions.items() if v[1].get("openapiVer", "").startswith("3.")),
                         key=lambda kv: (kv[1].get("added", ""), kv[0]))
        for (va, a), (vb, b) in zip(ordered, ordered[1:]):
            candidates.append((name, va, a, vb, b))
    sample = rng.sample(candidates, min(pairs, len(candidates)))
    out_dir = os.path.join(OUT, "apisguru")
    os.makedirs(out_dir, exist_ok=True)
    result = []
    for i, (name, va, a, vb, b) in enumerate(sample):
        entry = {"source": "apisguru", "api": name}
        ok = True
        for role, version, info in (("base", va, a), ("revision", vb, b)):
            safe = (name + "__" + version).replace(":", "_").replace("/", "_")
            target = os.path.join(out_dir, f"{i:03d}-{role}-{safe}.json")
            try:
                if not os.path.exists(target):
                    req = urllib.request.Request(info["swaggerUrl"], headers={"User-Agent": "axon-eval"})
                    with urllib.request.urlopen(req, timeout=180) as r:
                        body = r.read()
                    with open(target, "wb") as f:
                        f.write(body)
                entry[role] = {"file": f"apisguru/{os.path.basename(target)}", "version": version,
                               "url": info["swaggerUrl"], "sha256": sha(target),
                               "bytes": os.path.getsize(target)}
            except Exception as e:
                entry[role] = {"version": version, "url": info["swaggerUrl"], "download_error": str(e)}
                ok = False
        entry["usable"] = ok
        result.append(entry)
        print(f"apisguru {i:3d} {name} {va} -> {vb}", file=sys.stderr)
    return {"adjacent_version_pairs_available": len(candidates)}, result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", type=int, default=50)
    ap.add_argument("--seed", type=int, default=20261003)
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    manifest_path = os.path.join(ROOT, "eval", "datasets", "histories.json")
    manifest = {"seed": args.seed, "sources": {}, "pairs": []}
    if os.path.exists(manifest_path):
        manifest = json.load(open(manifest_path, encoding="utf-8"))
    wanted = [s for s in args.only.split(",") if s] or list(GIT_SOURCES) + ["apisguru"]
    for name in wanted:
        rng = random.Random(f"{args.seed}:{name}")
        if name == "apisguru":
            meta, pairs = apisguru(args.pairs, rng)
        else:
            url, path = GIT_SOURCES[name]
            meta, pairs = git_source(name, url, path, args.pairs, rng)
        manifest["sources"][name] = meta
        manifest["pairs"] = [p for p in manifest["pairs"] if p["source"] != name] + pairs
        with open(manifest_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(manifest, f, indent=2)
            f.write("\n")


if __name__ == "__main__":
    main()
