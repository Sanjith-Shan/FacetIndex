# FAISS filter-track baseline (M1)

This reproduces the NeurIPS'23 big-ann-benchmarks filter track FAISS baseline
(neurips23/filter/faiss/, repo commit 89a3aba) on the mini PC inside WSL2 (Ubuntu 24.04), without Docker.

## Files

- scripts/faiss_wsl_setup.sh: one-time environment install and selector build (idempotent).
- scripts/faiss_baseline.py: the runner. Imports the benchmark's own FAISS class unmodified.
- scripts/faiss_wsl.sh: activates the env and runs faiss_baseline.py with all arguments passed through.

## Install steps (what faiss_wsl_setup.sh does)

1. Download the static micromamba binary to /mnt/c/SullaPortal/data/facetindex/wsl/bin/micromamba.
   No sudo is available in the WSL distro (sudo -n fails), so nothing comes from apt.
2. Create the env at /mnt/c/SullaPortal/data/facetindex/wsl/mamba/envs/faiss with
   `micromamba create -c pytorch -c conda-forge python=3.10 faiss-cpu numpy scipy pyyaml h5py psutil requests swig=4.0.2 gxx_linux-64`.
   The env lives on /mnt/c and works fine there (imports take a few seconds).
3. In /mnt/c/SullaPortal/data/facetindex/wsl/faiss_build/, run the Dockerfile's two build commands:
   `swig -c++ -python -I$CONDA_PREFIX/include -Ifaiss bow_id_selector.swig`, then
   `g++ -shared -O3 -g -fPIC bow_id_selector_wrap.cxx -o _bow_id_selector.so -I <python include> -I $CONDA_PREFIX/include $CONDA_PREFIX/lib/libfaiss_avx2.so -Ifaiss`
   (using the conda g++, plus an rpath to the env lib dir).

## Versions

- Python 3.10.21 (conda-forge)
- faiss-cpu 1.13.2 from the pytorch channel (build py3.10_hf65b397_0_cpu, AVX2 library libfaiss_avx2.so, MKL 2024.2.2)
- SWIG 4.0.2 (conda-forge)
- g++ 16.2.0 (conda-forge gxx_linux-64)
- numpy 2.2.6, scipy 1.15.2, h5py 3.16.0, pyyaml 6.0.3, psutil 7.2.2
- micromamba 2.9.0

## Deviations from the Dockerfile

- No Docker and no Anaconda installer: micromamba in user space, because WSL has no passwordless sudo and
  nothing may be installed globally. The Dockerfile used `conda install -c pytorch faiss-cpu` with no version pin,
  so it would also pick up the current release today; we record 1.13.2.
- SWIG is pinned to 4.0.2. The pytorch faiss package is wrapped with SWIG 4.0.2 (runtime ABI 4). The latest
  conda-forge SWIG (4.5.1, runtime ABI 5) builds a module that does not share faiss's SWIG type table, so
  `IDSelectorBOW(..., faiss.swig_ptr(indptr), ...)` fails with a TypeError. The Dockerfile's apt swig on Ubuntu
  jammy is also 4.0.2, so the pin matches the original.
- g++ and SWIG come from conda-forge instead of apt; the python include dir comes from sysconfig instead of
  distutils (same path).
- `requests` is added only so benchmark/plotting/metrics.py (which imports the power sensor module) can be imported
  and its recall function used directly.
- The runner does not use run.py or Docker. It registers a dataset object (a YFCC100MDataset subclass pointed
  at explicit files) in benchmark.datasets.DATASETS and redirects index_name and binarysig_name on the FAISS
  instance to --index-path (the original writes to ./data/). Nothing in faiss.py or the selector is changed.

## Threads

The scoring VM config uses threads=16 on 8 vCPUs. The mini PC has 4 cores / 8 threads, but WSL is limited by
C:\Users\Sulla Claw\.wslconfig to processors=2 and memory=6GB (5.8 GiB visible, 2 GiB swap). The runner
defaults to --threads 4 (the FAISS class's query thread pool), which oversubscribes the 2 visible vCPUs;
pass --threads 2 to match what WSL actually sees. Each record stores cores_visible. Index build uses faiss's
default OpenMP thread count (all visible cores), as in the harness.

## Timing and recall

- Build: wall time of algo.fit(dataset), as in BaseRunner.build. If an index already exists at --index-path
  (plus <index-path>.binarysig) it is loaded instead and build_s is null, as in runner.run.
- Search: for each query-arg group (the yfcc-10M grid read from config.yaml, same order), call
  set_query_arguments, then per repeat time the wall clock of reading the query metadata plus
  filtered_query(X, metadata, 10) over all queries as one batch, exactly as FilterRunner.run_task does
  (query vectors are loaded before the timer). qps = nq / total_s. The harness reports the best of its
  run_count repeats; we write every repeat so the best can be taken later.
- Recall@10: benchmark/plotting/metrics.get_recall_values is called directly on (GT ids, GT distances) from
  benchmark/dataset_io.knn_result_read. That is the harness's knn recall: per query the size of the
  intersection of the GT id set and the result id set, divided by 10, averaged. Distance ties only extend the
  GT set when the GT file has more than k columns; YFCC GT has exactly 10, so ties do not matter here. GT
  padding entries of -1, if any, are counted the same way the harness counts them (gt_padding_entries in each
  record shows how many there are).
- n_bruteforce_path in each record is the number of queries whose word frequency product is below
  mt_threshold, i.e. that take the metadata-first exact path. It is computed outside the timed region.

## Smoke test

Synthetic data under /mnt/c/SullaPortal/data/facetindex/tmp/faiss_smoke/ (gen_smoke.py): 20k clustered uint8
192-d vectors, 400-word Zipf-like vocabulary with per-cluster topic words, 500 queries with 1 or 2 words and at
least 10 matches, brute-force filtered GT. IVF64,SQ8, threads 2: recall@10 0.40 at nprobe 1 rising to 0.993 at
nprobe 64 and 96 (the remaining gap is SQ8 error; IVF64,Flat at nprobe 64 gives 1.000). At mt_threshold 0.01,
267 of 500 queries take the brute-force path and recall reaches 0.996. Save, reload, repeats and
--limit-queries were also exercised.

## Memory

Measured on real YFCC slices (IVF1024,SQ8): build peak RSS 0.81 GiB at 500k and 1.39 GiB at 1M, about
1.16 GiB per million vectors. fit() hands the uint8 base to faiss train and add, which each make a full float32
copy (7.2 GiB at 10M), and IVF16384 k-means samples 4.19M float vectors (3.0 GiB). Expect the 10M build to
peak around 12 to 13 GiB. That does not fit in WSL as configured (5.8 GiB plus 2 GiB swap), and it would not fit
in the default half-of-host limit either. Loading a saved index and searching is much smaller: 0.46 GiB at
1M, expected about 4 to 5 GiB at 10M (2 GB index, about 0.9 GB metadata CSR, another 0.9 GB transient transpose
per batch; base vectors are memory mapped). The search sweep should fit.

## Full 10M run

Build plus sweep on public queries, then the private queries reusing the same index:

    wsl -d Ubuntu-24.04 -- bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl.sh --queries public --threads 2 --repeats 1 --out /mnt/c/SullaPortal/data/facetindex/results/m1_faiss.jsonl
    wsl -d Ubuntu-24.04 -- bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl.sh --queries private --threads 2 --repeats 1 --out /mnt/c/SullaPortal/data/facetindex/results/m1_faiss.jsonl

The index goes to /mnt/c/SullaPortal/data/facetindex/faiss_idx/yfcc-10M.nb10000000.IVF16384_SQ8.faissindex
(about 2 GB, plus an 80 MB .binarysig). Add --rebuild to force a rebuild, or --build-only to only build.
The build step needs WSL memory raised to about 14 GB (or another way to produce the index file); see Memory.

## 1M slice

--dataset-files BASE_U8BIN BASE_SPMAT QUERY_U8BIN QUERY_SPMAT GT_IBIN runs on any files. A 1M crop of the YFCC
base and metadata is at /mnt/c/SullaPortal/data/facetindex/tmp/faiss_mem/. The YFCC 10M queries cannot be
reused on it as is: some metadata-first queries match fewer than 10 of the first 1M documents, and faiss.py
then hits `assert len(docs) >= k, pdb.set_trace()` (the wrapper feeds /dev/null to stdin, so pdb exits with
BdbQuit instead of hanging). A 1M run needs a query subset with at least 10 matches each and a new GT.
