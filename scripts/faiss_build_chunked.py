#!/usr/bin/env python3
"""Build the FAISS filter-track baseline index in bounded memory.

The benchmark's own FAISS.fit() (neurips23/filter/faiss/faiss.py) converts all 10M uint8 vectors to
float32 twice (once to train, once to add), peaking near 12 to 13 GiB. WSL on the mini PC is capped at
6 GB by the box's .wslconfig, which this project does not change. This script performs the same steps
with the same parameters, in chunks:

  * BinarySignatures(meta_b, 0.1) from the benchmark module (same RandomState(123) signatures).
  * faiss.index_factory(192, "IVF16384,SQ8").
  * train on a random sample of 256 * nlist = 4,194,304 vectors. FAISS's k-means subsamples to exactly
    this many points when given more (max_points_per_centroid = 256), so passing the sample ourselves
    changes only which random points are drawn, not how many.
  * add_with_ids(xb, arange(nb) | db_sig) in chunks of 500k. IVF assignment and SQ8 encoding are per
    vector, so chunked adds give the same index as one add for a given trained quantizer.

The index and the pickled signatures are written where scripts/faiss_baseline.py looks for them, and
its search path (the benchmark's unchanged FAISS.filtered_query) loads them with FAISS.load_index.
"""
import argparse
import json
import os
import pickle
import resource
import sys
import time

BAB = "/mnt/c/SullaPortal/data/facetindex/src/big-ann-benchmarks"
BUILD = "/mnt/c/SullaPortal/data/facetindex/wsl/faiss_build"
DATA = "/mnt/c/SullaPortal/data/facetindex"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", default=DATA + "/yfcc")
    ap.add_argument("--index-path", default=DATA + "/faiss_idx/yfcc-10M.nb10000000.IVF16384_SQ8.faissindex")
    ap.add_argument("--indexkey", default="IVF16384,SQ8")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--chunk", type=int, default=500_000)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--out", default=DATA + "/results/faiss_build.jsonl")
    args = ap.parse_args()
    sys.path.insert(0, BUILD)
    sys.path.insert(0, BAB)
    import numpy as np
    import faiss
    from benchmark.dataset_io import read_sparse_matrix
    from neurips23.filter.faiss.faiss import BinarySignatures

    faiss.omp_set_num_threads(args.threads)
    t0 = time.time()
    base = os.path.join(args.data_dir, "base.10M.u8bin")
    n, d = np.fromfile(base, dtype=np.int32, count=2)
    n, d = int(n), int(d)
    xb = np.memmap(base, dtype=np.uint8, mode="r", offset=8, shape=(n, d))

    print("binary signatures", flush=True)
    meta_b = read_sparse_matrix(os.path.join(args.data_dir, "base.metadata.10M.spmat"))
    binsig = BinarySignatures(meta_b, 0.1)
    del meta_b
    os.makedirs(os.path.dirname(args.index_path), exist_ok=True)
    pickle.dump(binsig, open(args.index_path + ".binarysig", "wb"), -1)
    t_sig = time.time() - t0

    index = faiss.index_factory(d, args.indexkey)
    nlist = faiss.extract_index_ivf(index).nlist
    ntrain = min(n, 256 * nlist)
    rs = np.random.RandomState(args.seed)
    sample = np.sort(rs.choice(n, ntrain, replace=False))
    print("train on", ntrain, flush=True)
    xt = np.empty((ntrain, d), dtype=np.float32)
    for i0 in range(0, ntrain, args.chunk):
        xt[i0:i0 + args.chunk] = xb[sample[i0:i0 + args.chunk]]
    t1 = time.time()
    index.train(xt)
    del xt
    t_train = time.time() - t1

    print("add", flush=True)
    t2 = time.time()
    for i0 in range(0, n, args.chunk):
        i1 = min(n, i0 + args.chunk)
        ids = np.arange(i0, i1, dtype=np.int64) | binsig.db_sig[i0:i1]
        index.add_with_ids(np.ascontiguousarray(xb[i0:i1], dtype=np.float32), ids)
        print("  added", i1, flush=True)
    t_add = time.time() - t2
    faiss.write_index(index, args.index_path)
    row = {
        "experiment": "faiss_build", "indexkey": args.indexkey, "nb": n, "ntrain": ntrain, "threads": args.threads,
        "signatures_s": t_sig, "train_s": t_train, "add_s": t_add, "total_s": time.time() - t0,
        "peak_rss_gb": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1048576,
        "faiss": faiss.__version__, "index_path": args.index_path,
        "note": "chunked build, same factory string, training size and ids as the benchmark's FAISS.fit; WSL2 Ubuntu 24.04 on the mini PC (2 vCPU, 6 GB per .wslconfig)",
        "loadavg": open("/proc/loadavg").read().split()[:3],
    }
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "a") as f:
        f.write(json.dumps(row) + "\n")
    print(row, flush=True)


if __name__ == "__main__":
    main()
