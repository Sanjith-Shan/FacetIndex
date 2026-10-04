# NUMBERS

Every measured figure in this repository, with the results file it came from. Nothing here is
rounded past what the file says beyond the stated precision, and anything not measured is listed
as not measured. Machine for everything: the mini PC (AMD Ryzen 3 4300U, 4 cores and 4 threads,
14.9 GB RAM, Windows 11, Temurin JDK 21.0.12, Lucene 10.5.1), shared with other projects; each
results line records the machine load at start and end. FAISS ran in WSL2 Ubuntu 24.04 on the same
box, capped at 2 vCPUs and 6 GB by the box's `.wslconfig`.

## Headline

| Claim | Number | File |
|---|---|---|
| Planner on yfcc-10M, 10k stratified private queries, pinned to 2 cores (same core count as the FAISS baseline) | recall@10 **0.9123** at **1,151.4 QPS** | `results/m3_exp3.jsonl` (machine label `minipc-pinned-2-cores`) |
| FAISS filter baseline (benchmark code, `IVF16384,SQ8`, 1M-vector training sample), same queries, 2 threads, best QPS at recall@10 >= 0.90 across both measurement runs | **210.1 QPS** at recall 0.9073 (nprobe 64, mt_threshold 0.0001) | `results/m1_faiss.jsonl` |
| Ratio, same box, same queries, 2 cores each | **5.5x** (1,151.4 / 210.1) | the two rows above |
| Planner, 2 threads not pinned | 0.9123 at 1,699.2 QPS (8.1x the FAISS figure) | `results/m3_exp3.jsonl` |
| Planner, 4 threads | 0.9123 at 2,945.3 QPS | `results/m3_exp3.jsonl` |
| Best single configuration, 4 threads (recall >= 0.90) | S6 `ef=32000`: 0.9072 at 492.9 QPS; S2 `ef=16`: 0.9603 at 470.3 QPS | `results/exp1_10m.jsonl` |
| Planner over the best single configuration, 4 threads | 6.0x (2,945.3 / 492.9) | the two rows above |
| FAISS baseline's rule rebuilt inside FacetIndex (S0 below mt_threshold, S4 above), best at recall >= 0.90, 4 threads | 0.9518 at 1,018.1 QPS (mt 0.001, `S4:c=16384,nprobe=64`) | `results/m3_exp3.jsonl` |
| Constructed streaming workload, A3 external store through Kafka | 10,000 SetAttrs/s offered, all applied, no backlog after the window | `results/exp5.jsonl` |

**Context, not a comparison.** The competition's leaderboard ran on an 8-vCPU Azure D8lds v5: FAISS
3,253 QPS and the winner (ParlayANN IVF²) 37,671 QPS on the private queries, about 11.6x; Pinecone
85,492 and Zilliz 84,596 QPS (closed source, public queries), about 28x FAISS's 3,033 on that set.
Those absolute numbers come from different hardware than every number above and are not comparable
to them; only the ratios against FAISS measured on the same machine are.

**Query sample.** 10,000 private queries, 2,000 drawn from each of five selectivity bins
(`fi subset`, seed 20261004; the bin sizes in the full set are in `results/m0_data.jsonl`). Equal
bins give the rarest bin (over 10% selectivity, 5.7% of the full set) a 20% share. Both FAISS and
FacetIndex ran on exactly these queries, so the ratio is like for like. Population-weighted recall
and QPS for FacetIndex rows are computed from per-bin results in the tables below (FAISS's harness
times the batch as a whole, so it has no per-bin breakdown).

**Caveats that travel with these numbers.**
- The FAISS index was trained on a 1,048,576-vector sample instead of FAISS's default
  256 x 16,384 = 4,194,304, because the full training did not fit the night's schedule on 2 vCPUs
  (`results/m1_faiss_build.jsonl`). Everything else matches the benchmark's `fit` and the search is
  the benchmark's unchanged code.
- The first FAISS grid ran while another project's jobs loaded the shared WSL VM (load average 2
  to 5); the second run (`q10k-stratified-quiet-rerun`, 2 repeats) had WSL nearly idle. The headline
  uses the highest FAISS QPS from either run.
- The planner's "QPS" is live wall-clock throughput of the planner choosing and running each query.
  The oracle's throughput is derived from per-query latencies measured in the 4-thread sweep (it
  cannot be run live), so compare it with the planner's "QPS from latencies" column. The oracle rows
  labelled 2 threads reuse 4-thread latencies and are not meaningful.
- The public calibration sweep ran alone; an earlier attempt that overlapped the FAISS build was
  stopped and discarded.
