#!/usr/bin/env python3
"""Independent brute-force check of a filtered ground-truth file (numpy, no shared code with the JVM side).

For a sample of queries it intersects the query's tag columns in the CSR base metadata, computes exact
squared L2 over the matching uint8 vectors in int64, and compares the top-10 ids and distances with the
GT file. Appends one JSON line per query set to --out.

    python scripts/verify_gt.py --data C:/SullaPortal/data/facetindex/yfcc1m --out results/m0_data.jsonl
"""
import argparse
import json
import os
import platform
import time

import numpy as np

KEY = 2727415019


def read_u8bin(path):
    n, d = np.fromfile(path, dtype=np.int32, count=2)
    return np.memmap(path, dtype=np.uint8, mode="r", offset=8, shape=(int(n), int(d)))


def read_spmat(path):
    with open(path, "rb") as f:
        nrow, ncol, nnz = np.fromfile(f, dtype=np.int64, count=3)
        indptr = np.fromfile(f, dtype=np.int64, count=nrow + 1)
        indices = np.fromfile(f, dtype=np.int32, count=nnz)
    return int(nrow), int(ncol), indptr, indices


def read_ibin(path):
    nq, k = np.fromfile(path, dtype=np.int32, count=2)
    ids = np.fromfile(path, dtype=np.int32, count=nq * k, offset=8).reshape(nq, k)
    dists = np.fromfile(path, dtype=np.float32, count=nq * k, offset=8 + 4 * nq * k).reshape(nq, k)
    return ids, dists


def columns_of(nrow, indptr, indices, ncol):
    """Transpose the CSR to per-column sorted row lists (docs per word)."""
    rows = np.repeat(np.arange(nrow, dtype=np.int32), np.diff(indptr))
    order = np.argsort(indices, kind="stable")
    col_sorted = indices[order]
    rows_sorted = rows[order]
    starts = np.searchsorted(col_sorted, np.arange(ncol + 1))
    return starts, rows_sorted


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=2000)
    ap.add_argument("--name", default="yfcc-1M-slice")
    ap.add_argument("--base", default=None)
    args = ap.parse_args()
    base_file = args.base or next(f for f in ["base.u8bin", "base.10M.u8bin"] if os.path.exists(os.path.join(args.data, f)))
    meta_file = "base.metadata.spmat" if base_file == "base.u8bin" else "base.metadata.10M.spmat"
    xb = read_u8bin(os.path.join(args.data, base_file))
    nrow, ncol, indptr, indices = read_spmat(os.path.join(args.data, meta_file))
    starts, rows_sorted = columns_of(nrow, indptr, indices, ncol)
    for which, qf, mf, gf in [
        ("public", "query.public.100K.u8bin", "query.metadata.public.100K.spmat", "GT.public.ibin"),
        ("private", f"query.private.{KEY}.100K.u8bin", f"query.metadata.private.{KEY}.100K.spmat", f"GT.private.{KEY}.ibin"),
    ]:
        t0 = time.time()
        xq = read_u8bin(os.path.join(args.data, qf))
        _, _, qptr, qidx = read_spmat(os.path.join(args.data, mf))
        gt_ids, gt_d = read_ibin(os.path.join(args.data, gf))
        nq = xq.shape[0]
        step = max(1, nq // args.limit)
        picks = list(range(0, nq, step))[: args.limit]
        same_sets = 0
        hits = 0
        possible = 0
        max_dd = 0.0
        for q in picks:
            words = qidx[qptr[q]: qptr[q + 1]]
            cand = rows_sorted[starts[words[0]]: starts[words[0] + 1]]
            for w in words[1:]:
                cand = np.intersect1d(cand, rows_sorted[starts[w]: starts[w + 1]], assume_unique=True)
            if len(cand) == 0:
                mine = np.array([], dtype=np.int64)
                md = np.array([], dtype=np.float64)
            else:
                diff = xb[np.sort(cand)].astype(np.int64) - xq[q].astype(np.int64)
                dist = (diff * diff).sum(axis=1)
                srt = np.sort(cand)
                order = np.lexsort((srt, dist))[:10]
                mine = srt[order]
                md = dist[order].astype(np.float64)
            g = gt_ids[q][gt_ids[q] >= 0]
            hits += len(set(g.tolist()) & set(mine.tolist()))
            possible += len(g)
            if set(g.tolist()) == set(mine.tolist()):
                same_sets += 1
            n = min(len(md), len(g))
            if n:
                max_dd = max(max_dd, float(np.abs(md[:n] - gt_d[q][:n]).max()))
        row = {
            "experiment": "m0_gt_verify", "dataset": args.name, "query_set": which,
            "queries_checked": len(picks), "query_stride": step,
            "gt_entries_found_by_numpy": hits / max(1, possible), "gt_entries_checked": possible,
            "queries_with_identical_id_sets": same_sets, "max_abs_distance_diff": max_dd,
            "method": "independent numpy brute force (int64 L2 over np.intersect1d of tag columns)",
            "seconds": round(time.time() - t0, 1),
            "machine": {"label": "minipc", "cpu": platform.processor(), "python": platform.python_version(), "numpy": np.__version__},
            "repeats": 1,
        }
        with open(args.out, "a") as f:
            f.write(json.dumps(row) + "\n")
        print(row)


if __name__ == "__main__":
    main()
