#!/usr/bin/env python3
"""FacetIndex M1: FAISS filtered-search baseline (NeurIPS'23 big-ann-benchmarks filter track).

Drives the benchmark's own FAISS class (neurips23/filter/faiss/faiss.py) the way the harness
does (benchmark/runner.py + neurips23/filter/run.py), without Docker:

  * build once (algo.fit, timed like BaseRunner.build) or reuse a saved index (algo.load_index)
  * for each query-arg group: algo.set_query_arguments(qa), then per repeat time the wall clock of
    "read query metadata + algo.filtered_query(X, metadata, k)" exactly like FilterRunner.run_task
  * recall@k via benchmark/plotting/metrics.get_recall_values (the harness's knn recall, with
    distance-tie handling) against the GT .ibin read by benchmark/dataset_io.knn_result_read

The algorithm code is imported unmodified. The only adaptations are: a dataset object registered
in benchmark.datasets.DATASETS that points at explicit files, and index_name/binarysig_name
redirected on the instance to --index-path (the original writes to ./data/).

One JSON line per (query-args, repeat) is appended to --out. Per-query output is never written.
"""
import argparse
import json
import os
import platform
import resource
import sys
import time

DATA_ROOT = "/mnt/c/SullaPortal/data/facetindex"
DEFAULT_BAB = os.path.join(DATA_ROOT, "src", "big-ann-benchmarks")
DEFAULT_BUILD = os.path.join(DATA_ROOT, "wsl", "faiss_build")
NOTE = "WSL2 Ubuntu 24.04 on the mini PC"
PRIVATE_KEY = 2727415019


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--queries", choices=["public", "private"], default="public")
    p.add_argument("--data-dir", default=os.path.join(DATA_ROOT, "yfcc"),
                   help="directory holding the YFCC-10M files (standard names)")
    p.add_argument("--dataset-files", nargs=5, metavar=("BASE_U8BIN", "BASE_SPMAT", "QUERY_U8BIN", "QUERY_SPMAT", "GT_IBIN"),
                   help="explicit files; overrides --data-dir and --queries file selection")
    p.add_argument("--index-path", default=None,
                   help="faiss index file (binary signatures go to <index-path>.binarysig). "
                        "Default: <data root>/faiss_idx/<dataset tag>.<indexkey>.faissindex")
    p.add_argument("--indexkey", default="IVF16384,SQ8")
    p.add_argument("--threads", type=int, default=4, help="index param 'threads' (query thread pool size)")
    p.add_argument("--repeats", type=int, default=1)
    p.add_argument("--limit-queries", type=int, default=0, help="use only the first N queries (0 = all)")
    p.add_argument("--k", type=int, default=10)
    p.add_argument("--rebuild", action="store_true", help="build even if a saved index exists")
    p.add_argument("--build-only", action="store_true", help="build/load the index and exit")
    p.add_argument("--grid-config", default=None,
                   help="config.yaml to read the query-arg grid from (default: the benchmark's faiss config)")
    p.add_argument("--grid-dataset", default="yfcc-10M", help="dataset section of config.yaml for the grid")
    p.add_argument("--nprobe", type=int, nargs="*", default=None, help="override grid: nprobe values")
    p.add_argument("--mt-threshold", type=float, nargs="*", default=None, help="override grid: mt_threshold values")
    p.add_argument("--out", default=os.path.join(DATA_ROOT, "results", "m1_faiss.jsonl"))
    p.add_argument("--tag", default=None, help="free-form label stored in each record")
    p.add_argument("--bab-root", default=DEFAULT_BAB)
    p.add_argument("--build-dir", default=DEFAULT_BUILD, help="dir containing _bow_id_selector.so")
    return p.parse_args()


# ----------------------------------------------------------------- machine info

def cpu_model():
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.processor()


def mem_total_gb():
    with open("/proc/meminfo") as f:
        for line in f:
            if line.startswith("MemTotal:"):
                return round(int(line.split()[1]) / 2**20, 2)
    return None


def loadavg():
    with open("/proc/loadavg") as f:
        return [float(x) for x in f.read().split()[:3]]


def peak_rss_gb():
    return round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 2**20, 3)


def xbin_header(fn):
    import numpy as np
    n, d = map(int, np.fromfile(fn, dtype="uint32", count=2))
    return n, d


# ----------------------------------------------------------------- main

def main():
    args = parse_args()
    sys.path.insert(0, args.build_dir)
    sys.path.insert(0, args.bab_root)

    import numpy as np
    import yaml
    import faiss
    from benchmark import datasets as bds
    from benchmark.dataset_io import knn_result_read
    from benchmark.plotting.metrics import get_recall_values
    from neurips23.filter.faiss.faiss import FAISS

    # ---- dataset files
    if args.dataset_files:
        base_fn, base_meta_fn, q_fn, q_meta_fn, gt_fn = [os.path.abspath(x) for x in args.dataset_files]
        ds_tag = "custom-" + os.path.splitext(os.path.basename(base_fn))[0]
    else:
        dd = args.data_dir
        base_fn = os.path.join(dd, "base.10M.u8bin")
        base_meta_fn = os.path.join(dd, "base.metadata.10M.spmat")
        if args.queries == "public":
            q_fn = os.path.join(dd, "query.public.100K.u8bin")
            q_meta_fn = os.path.join(dd, "query.metadata.public.100K.spmat")
            gt_fn = os.path.join(dd, "GT.public.ibin")
        else:
            q_fn = os.path.join(dd, "query.private.%d.100K.u8bin" % PRIVATE_KEY)
            q_meta_fn = os.path.join(dd, "query.metadata.private.%d.100K.spmat" % PRIVATE_KEY)
            gt_fn = os.path.join(dd, "GT.private.%d.ibin" % PRIVATE_KEY)
        ds_tag = "yfcc-10M"
    for fn in (base_fn, base_meta_fn, q_fn, q_meta_fn, gt_fn):
        if not os.path.exists(fn):
            sys.exit("missing file: %s" % fn)

    class FilesDataset(bds.YFCC100MDataset):
        """YFCC100MDataset (knn_filtered, euclidean, uint8) pointed at explicit files.
        basedir is "" so os.path.join(basedir, fn) returns the absolute file names unchanged."""
        def __init__(self):
            super().__init__(filtered=True)
            self.basedir = ""
            self.nb, self.d = xbin_header(base_fn)
            self.nq = xbin_header(q_fn)[0]
            self.private_nq = self.nq
            self.ds_fn, self.ds_metadata_fn = base_fn, base_meta_fn
            self.qs_fn, self.qs_metadata_fn, self.gt_fn = q_fn, q_meta_fn, gt_fn
            self.qs_private_fn, self.qs_private_metadata_fn = q_fn, q_meta_fn
            self.gt_private_fn = self.private_gt_fn = gt_fn

    ds_name = "facetindex-" + ds_tag
    bds.DATASETS[ds_name] = lambda: FilesDataset()
    ds = bds.DATASETS[ds_name]()
    if ds.nb > 10**7:
        sys.exit("base has %d vectors; the benchmark's get_dataset() asserts nb <= 1e7" % ds.nb)

    index_path = args.index_path or os.path.join(
        DATA_ROOT, "faiss_idx", "%s.nb%d.%s.faissindex" % (ds_tag, ds.nb, args.indexkey.replace(",", "_")))
    os.makedirs(os.path.dirname(os.path.abspath(index_path)), exist_ok=True)
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)

    # ---- query-arg grid (same order as config.yaml)
    if args.nprobe is not None or args.mt_threshold is not None:
        nps = args.nprobe or [1, 4, 16, 32, 64, 96]
        mts = args.mt_threshold or [0.0001, 0.0003, 0.01]
        grid = [{"nprobe": n, "mt_threshold": m} for m in mts for n in nps]
    else:
        cfg_fn = args.grid_config or os.path.join(args.bab_root, "neurips23", "filter", "faiss", "config.yaml")
        with open(cfg_fn) as f:
            cfg = yaml.safe_load(f)
        rg = cfg[args.grid_dataset]["faiss"]["run-groups"]["base"]
        grid = yaml.safe_load(rg["query-args"])

    machine = {
        "cpu_model": cpu_model(),
        "cores_visible": len(os.sched_getaffinity(0)),
        "mem_total_gb": mem_total_gb(),
        "kernel": platform.release(),
    }
    print("machine:", machine, flush=True)
    print("faiss", faiss.__version__, "| dataset", ds_name, "nb", ds.nb, "d", ds.d, "| index", index_path, flush=True)

    # ---- build or load (runner.run: rebuild or not algo.load_index -> BaseRunner.build)
    index_params = {"indexkey": args.indexkey, "binarysig": True, "threads": args.threads}
    algo = FAISS("euclidean", index_params)
    algo.index_name = lambda name: index_path
    algo.binarysig_name = lambda name: index_path + ".binarysig"

    build_s = None
    load_s = None
    t0 = time.time()
    if args.rebuild or not algo.load_index(ds_name):
        t0 = time.time()
        algo.fit(ds_name)
        build_s = time.time() - t0
        print("Built index in", build_s, flush=True)
    else:
        load_s = time.time() - t0
        print("Loaded existing index in %.1f s" % load_s, flush=True)
    build_peak_rss = peak_rss_gb()
    print("peak RSS after build/load: %.2f GB" % build_peak_rss, flush=True)
    if args.build_only:
        return

    # ---- queries and GT (loaded outside the timed region, as in run_task)
    X = ds.get_private_queries() if args.queries == "private" else ds.get_queries()
    gt_I, gt_D = knn_result_read(gt_fn)
    nq = X.shape[0]
    if args.limit_queries and args.limit_queries < nq:
        nq = args.limit_queries
        X = X[:nq]
    gt_I, gt_D = gt_I[:nq], gt_D[:nq]

    # Diagnostic only (outside timing): how many queries take the metadata-first brute-force path
    # for a given mt_threshold, using the same frequency rule as FAISS.filtered_query.
    meta_q_all = ds.get_queries_metadata()[:nq]
    freq_per_word = np.bincount(algo.meta_b.indices, minlength=algo.meta_b.shape[1]) / algo.nb
    qfreq = np.empty(nq)
    for q in range(nq):
        w = meta_q_all.indices[meta_q_all.indptr[q]:meta_q_all.indptr[q + 1]]
        qfreq[q] = np.prod(freq_per_word[w])
    gt_pad = int((gt_I[:, :args.k] < 0).sum())

    def read_query_metadata():
        m = ds.get_private_queries_metadata() if args.queries == "private" else ds.get_queries_metadata()
        return m[:nq] if nq < m.shape[0] else m

    for qa in grid:
        algo.set_query_arguments(qa)
        n_bf = int((qfreq < algo.metadata_threshold).sum())
        for rep in range(args.repeats):
            load_start = loadavg()
            start = time.time()
            metadata = read_query_metadata()
            algo.filtered_query(X, metadata, args.k)
            total = time.time() - start
            results = algo.get_results()
            load_end = loadavg()
            assert results.shape[0] == nq
            recall, _, _, n_ties = get_recall_values((gt_I, gt_D), results, args.k)
            rec = {
                "experiment": "m1_faiss",
                "tag": args.tag,
                "dataset": ds_tag,
                "nb": int(ds.nb),
                "indexkey": args.indexkey,
                "binarysig": True,
                "nprobe": qa.get("nprobe"),
                "mt_threshold": qa.get("mt_threshold"),
                "threads": args.threads,
                "k": args.k,
                "nq": int(nq),
                "query_set": args.queries if not args.dataset_files else "custom",
                "recall_at_10" if args.k == 10 else "recall_at_%d" % args.k: float(recall),
                "qps": nq / total,
                "total_s": total,
                "build_s": build_s,
                "index_load_s": load_s,
                "repeat": rep,
                "n_bruteforce_path": n_bf,
                "gt_ties_queries": int(n_ties),
                "gt_padding_entries": gt_pad,
                "peak_rss_gb_build": build_peak_rss,
                "peak_rss_gb": peak_rss_gb(),
                "faiss_version": faiss.__version__,
                "python": platform.python_version(),
                "machine": machine,
                "loadavg_start": load_start,
                "loadavg_end": load_end,
                "index_path": index_path,
                "files": {"base": base_fn, "base_meta": base_meta_fn, "query": q_fn,
                          "query_meta": q_meta_fn, "gt": gt_fn},
                "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "note": NOTE,
            }
            with open(args.out, "a") as f:
                f.write(json.dumps(rec) + "\n")
            print("nprobe=%s mt=%s rep=%d  recall@%d=%.4f  qps=%.1f  total=%.2fs  bruteforce=%d/%d" % (
                qa.get("nprobe"), qa.get("mt_threshold"), rep, args.k, recall, nq / total, total, n_bf, nq),
                flush=True)


if __name__ == "__main__":
    main()
