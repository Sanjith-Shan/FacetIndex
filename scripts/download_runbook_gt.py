#!/usr/bin/env python3
"""Download the per-checkpoint ground truth of a streaming runbook (as benchmark/streaming/download_gt.py does).

    python scripts/download_runbook_gt.py --dataset msturing-10M-clustered --runbook delete_runbook.yaml --out msturing10m/delete_runbook_gt
Files land under $FACETINDEX_DATA/<out>/step<N>.gt100, N being the 1-based runbook step of each search.
"""
import argparse
import os
import sys
import urllib.request

import yaml

sys.path.insert(0, os.path.dirname(__file__))
from download import DATA, fetch  # noqa: E402

BAB = os.path.join(DATA, "src", "big-ann-benchmarks", "neurips23", "runbooks")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True)
    ap.add_argument("--runbook", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rb = yaml.safe_load(open(os.path.join(BAB, a.runbook)))[a.dataset]
    url = rb["gt_url"]
    out = os.path.join(DATA, a.out)
    os.makedirs(out, exist_ok=True)
    i = 1
    while i in rb:
        if rb[i]["operation"] == "search":
            fetch(f"{url}/step{i}.gt100", os.path.join(out, f"step{i}.gt100"))
        i += 1


if __name__ == "__main__":
    main()
