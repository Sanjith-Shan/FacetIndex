# FacetIndex

[![ci](https://github.com/Sanjith-Shan/FacetIndex/actions/workflows/ci.yml/badge.svg)](https://github.com/Sanjith-Shan/FacetIndex/actions/workflows/ci.yml)

FacetIndex is a filtered and streaming vector search engine in Kotlin on Apache Lucene 10.5.1: each
query is a vector plus tag predicates, a planner picks one of several filter strategies per query
from the exact number of matching items, and inserts, deletes and attribute updates stream in
through Kafka. It is measured on the NeurIPS'23 Big-ANN filtered track dataset (`yfcc-10M`: 10M CLIP
image vectors, 192-dim uint8, tags from a 200,386-word vocabulary, CC BY 4.0), on a 4-core AMD Ryzen 3
4300U mini PC with 14.9 GB RAM, not on the competition's 8-vCPU Azure D8lds v5 scoring VM, so its
absolute QPS is never presented as a leaderboard result: the comparison it claims is a ratio against
the track's own FAISS baseline rerun on the same machine with the same queries. The streaming numbers
come from a workload this project constructed on YFCC, not from a competition track. It does not
claim a new ANN index: the HNSW graph and the ACORN-1 style filtered traversal (strategies S2 and S3)
are Lucene's code. What this project built is the SIMD pre-filter kernel (S0), the IVF with
posting-list intersection (S4, S4-L), the per-tag sub-indexes (S6), the cost-model planner, the
external attribute store, the Kafka update path, and every measurement.

Every measured number is in [`NUMBERS.md`](NUMBERS.md) with the results file it came from. The
design and its sources are in [`DESIGN.md`](DESIGN.md), bugs in [`BUG_LOG.md`](BUG_LOG.md), and a
readable write-up in [`docs/WRITEUP.md`](docs/WRITEUP.md).

## Results at a glance

All on the 4-core mini PC; files in `results/`, every figure listed in `NUMBERS.md`.

| | Result | File |
|---|---|---|
| Planner vs the track's FAISS baseline, same box and queries, 2 cores each | recall@10 0.912 at 1,151 QPS vs FAISS's best 210 QPS at recall >= 0.9: **5.5x** | `m3_exp3.jsonl`, `m1_faiss.jsonl` |
| Planner vs the best single strategy, 4 threads | 2,945 QPS vs 493 (per-tag IVF) at recall >= 0.9: **6.0x** | `m3_exp3.jsonl`, `exp1_10m.jsonl` |
| Which strategy wins where | brute force under 0.1% selectivity, IVF and per-tag IVF from 0.1% to 10%, post-filtering above 10%; Lucene's filtered HNSW reaches 0.9 everywhere but is fastest nowhere | `exp1_10m.jsonl` |
| Attribute updates through Kafka (constructed workload) | external store: 10,000 SetAttrs/s applied with no backlog, 16.7 ms median to visible; tags as doc values: 235 s p99 to visible at 1,000/s | `exp5.jsonl` |

The competition's leaderboard ran on an 8-vCPU Azure D8lds v5 (FAISS 3,253 QPS, winner 37,671 on
private queries); those numbers are context only and are not comparable to any number above.

## What is measured, and how

- **Queries.** Static results use a stratified sample of 10,000 private queries (2,000 from each of
  five selectivity bins, seeded; `fi subset`). The planner is fitted on a sample of 10,000 public
  queries drawn the same way and scored on the private sample. Equal bins over-weight the rarest bin
  (over 10% selectivity is 5.7% of the full query set and 20% of the sample); `NUMBERS.md` also gives
  population-weighted figures computed from the per-bin results.
- **Ground truth.** The organizers' published filtered GT, checked against an independent numpy
  brute force on 1,000 queries per query set (identical ids and distances).
- **The bar.** The benchmark's own FAISS filter baseline (`neurips23/filter/faiss`, `IVF16384,SQ8`
  with binary signatures and the `mt_threshold` brute-force rule, unchanged search code), rebuilt and
  rerun on the same mini PC. WSL on this box is capped at 2 vCPUs and 6 GB by its `.wslconfig`,
  which this project did not change, so FAISS runs on 2 threads and the ratio compares FacetIndex at
  2 threads against it (FacetIndex at 4 threads is reported separately). The FAISS index was built
  in chunks to fit 6 GB (same factory string, training size and ids as the benchmark's `fit`; see
  `scripts/FAISS_BASELINE.md`).
- **Machine and load.** Every line in `results/*.jsonl` carries the CPU, cores, RAM, Java and Lucene
  versions, whole-machine CPU load and free memory at start and end, and the repeat count. The box is
  shared with other projects; where another job ran at the same time the line says so.

## Layout

| Path | What |
|---|---|
| `src/main/kotlin/facetindex/strategy` | The filter strategies behind one `FilterStrategy` interface |
| `src/main/kotlin/facetindex/planner` | Cost models fitted per configuration and the per-query planner |
| `src/main/kotlin/facetindex/attrs` | The external attribute store (per-tag RoaringBitmaps, per-row seqlocks) |
| `src/main/kotlin/facetindex/index` | Lucene index wrapper, filter queries, NRT lag sampling, merge timing |
| `src/main/kotlin/facetindex/ivf` | k-means (written here), IVF cluster bitmaps, per-tag IVF (S6) |
| `src/main/kotlin/facetindex/stream` | Kafka producer and idempotent consumer, the applier (A1/A2/A3) |
| `src/main/kotlin/facetindex/api` | The gRPC service (`Search`, `Upsert`, `Delete`, `SetAttrs`, `Stats`) |
| `src/main/java/facetindex/simd` | uint8 / int8 / float L2 kernels on the JDK Vector API |
| `src/main/kotlin/facetindex/bench`, `cli` | Commands that build indexes and write `results/*.jsonl` |
| `scripts/` | Download (size checks, disk guard), GT check, FAISS baseline, Kafka service, plots, experiment runner |

## Reproducing

Data lives under `$FACETINDEX_DATA` (default `C:/SullaPortal/data/facetindex`), never in the repo.
`scripts/download.py` checks every file's size against the server and refuses to start when free disk
would drop under 20 GB.

```bash
./gradlew build installDist jar           # unit, property and gRPC tests; Kafka test with Docker or FACETINDEX_KAFKA
python scripts/download.py yfcc           # about 3 GB
bash scripts/run_experiments.sh m0        # results/m0_data.jsonl
bash scripts/run_experiments.sh build     # IVF (4,096 and 16,384 clusters), 10M Lucene index, per-tag IVFs
bash scripts/run_experiments.sh subset    # the stratified 10k query samples
bash scripts/run_experiments.sh exp1-q10k-private   # results/exp1_10m.jsonl
bash scripts/run_experiments.sh exp1-q10k-public    # results/exp1_10m_public.jsonl (planner calibration)
bash scripts/run_experiments.sh faiss     # results/m1_faiss.jsonl (in WSL)
bash scripts/run_experiments.sh exp3-q10k # results/m3_exp3.jsonl
bash scripts/run_experiments.sh exp5      # results/exp5.jsonl (needs the Kafka broker: scripts/kafka_service.ps1 start)
```

`scripts/minipc_run.ps1 <phase>` runs the same phases from PowerShell on the mini PC.

## Not done (cut for time, or future work)

These were in the original plan and were not built or not measured; nothing in `NUMBERS.md` refers
to them.

- **M5 range filters (exp7).** Range predicates are implemented (`RangeStore`, ranges in every
  strategy and as Lucene points filters) but not measured. The `arxiv-for-fanns` dataset the plan
  named carries no license on Hugging Face or GitHub, so it was not downloaded.
- **Official streaming runbook (exp4).** `RunbookBench` replays `msturing-10M-clustered` with
  `delete_runbook.yaml` through Kafka and scores it the track's way; the data and per-step GT are
  downloaded, but the run was not made.
- **M6.** The 30M `final_runbook.yaml`, ACORN-γ construction as an experiment (S5 is wired but not
  measured). S6 (per-tag sub-indexes) was built and is measured.
- **exp8** (the Azure D8lds v5 rerun): a ready runbook is in `scripts/azure_d8lds_v5.md`; it costs
  money and was not run.
- The 1M-slice curves, the tombstone-drift experiment, and an upstream Lucene write-up.
