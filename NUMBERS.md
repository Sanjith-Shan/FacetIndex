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
| Constructed streaming workload, A3 external store through Kafka | 10,000 SetAttrs/s offered, 9,999.7/s applied, no backlog when the generator stopped | `results/exp5.jsonl` |
| Attribute change to visible (A3, produce to applied), 10,000/s | median **16.7 ms** (p99 2.60 s; see the streaming section) | `results/exp5.jsonl` |

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

## Streaming (constructed workload, not a competition track)

Start: the first 9M YFCC items (a copy of the force-merged 9M index). During a 150 s window: 100
inserts/s of the remaining items, 100 deletes/s of random live items, and SetAttrs at the stated rate
(add a frequent tag, or remove one of the item's own tags), produced into Kafka 4.3.1 (one broker in
WSL, 4 partitions) and applied by one consumer; an open-loop query load at 10 queries/s alternates
S2 and S4 (nprobe 32, 4,096 cells); filtered recall is checked against exact answers over the live
set every 50 s; NRT refresh every 1,000 ms. 1 repeat each. File: `results/exp5.jsonl`.

| Run | SetAttrs/s offered | applied/s (generator window) | backlog when the generator stopped | produce-to-applied p50 / p99 | Lucene write-to-visible p50 / p99 | query p50 / p99, S2 | checkpoint recall S2 / S4 | merge s per min |
|---|---|---|---|---|---|---|---|---|
| A3 external store, Kafka | 100 | 100.0 | 0 | 17.3 ms / 20.1 s | 0.82 s / 19.4 s | 21.6 ms / 7.60 s | 0.991 / 0.794 | 1.8 |
| A3 external store, Kafka | 1,000 | 999.9 | 0 | 16.0 ms / 5.68 s | 0.70 s / 5.60 s | 16.2 ms / 0.99 s | 0.991 / 0.792 | 6.5 |
| A3 external store, Kafka | 10,000 | 9,999.7 | 0 | 16.7 ms / 2.60 s | 0.75 s / 3.04 s | 18.7 ms / 1.33 s | 0.991 / 0.794 | 2.3 |
| A1 tags as terms, Kafka | 1,000 | 999.9 | 0 | 16.4 ms / 1.13 s | 0.76 s / 2.34 s | 19.8 ms / 1.14 s | 0.9925 / 0.792 | 23.8 |
| A2 tags as doc values, Kafka | 1,000 | 998.5 | 0 | 134.7 s / 213.8 s | 51.7 s / 235.5 s | 142.5 s / 247.6 s | 0.9935 / 0.7715 (one checkpoint) | 0.2 |
| exp9: A3, in process (no Kafka) | 10,000 | 9,999.4 | 0 | 0 us as recorded (applied on the producing thread) | 0.65 s / 1.50 s | 13.6 ms / 0.25 s | 0.991 / 0.7945 | 1.7 |

Reading it: for A3, produce-to-applied is also produce-to-visible (S0, S4, S6 read the store, and
S2/S3 see it through a never-cached filter), so the attribute freshness is the 16 to 17 ms median.
Its p99 does not grow with the rate (worst at 100/s), which points at stalls of the single consumer
thread behind the Lucene writes for inserts and deletes rather than at attribute load. The A2 run
saturated the box: each refresh rewrites the doc-value field for the 9M-document segment (index
2.97 to 3.37 GB in 150 s) and its filter scans every document. Earlier A3 rows measured as the
first run in a fresh process (a cold start) were archived outside the repo (`BUG_LOG.md` #10).

**exp6 (mixed workload) saturated the box at both rates tried.** With the planner, S2 and S4
alternating in one open-loop query pool while 1,000 SetAttrs/s, 100 inserts/s and 100 deletes/s
streamed through Kafka for 300 s, query latency from intended send time had a median of 36.4 s at
20 queries/s and 45.6 s at 10 queries/s (p99 128 s and 101 s), for all three strategies alike
(`results/exp6.jsonl`). The updates still kept up (1,000/s applied, no backlog) and checkpoint
recall held (planner 0.894, S2 0.993). The same box served 10 queries/s of S2 and S4 alone during
exp5 with an 18.7 ms median, so the difference is the planner's mix: calibrated on the static index,
it sends about a quarter of the queries to post-filtering with fetches of up to 10,000 candidates,
which are expensive, and everything queues behind them; memory pressure (a 7.6 GB working set on
14.9 GB) adds to it. So there is no clean mixed-load p99 from this run; the finding is that a
planner calibrated without update load can overload a live index.

## Data and ground truth (`results/m0_data.jsonl`)

| Fact | Value |
|---|---|
| Items, vocabulary, tag entries | 10,000,000 items; 200,386 words (200,363 used); 108,210,476 entries, 10.82 per item |
| Query sets | public 61,626 one-tag and 38,374 two-tag; private 61,778 and 38,222; no query matches nothing |
| Private selectivity bins (<0.01%, 0.01-0.1%, 0.1-1%, 1-10%, >10%) | 24,178 / 22,713 / 22,990 / 24,370 / 5,749 queries; median selectivity 0.14% (14,156 matches) |
| Published GT checked by an independent numpy brute force | 1,000 public and 1,000 private queries: every id set identical, max distance difference 0.0 |
| 1M development slice GT (recomputed) checked the same way | 2,000 + 2,000 queries identical (the slice curves themselves were cut) |

## Builds (`results/m2_build.jsonl`, `results/m1_faiss_build.jsonl`)

| Build | Time | Size |
|---|---|---|
| k-means, 4,096 cells (500k sample, 10 iterations) and assigning 10M | 299 s + 361 s | |
| k-means, 16,384 cells (1,048,576 sample) and assigning 10M | 3,443 s + 2,237 s | |
| Lucene HNSW (M 16, beam 100), first 9M items: add with 4 threads, then force-merge 17 segments | 5,103 s + 10,662 s (under memory pressure) | 2.97 GB |
| Last 1M appended and merged into the 10M index | 194 s + 2,339 s | 3.32 GB |
| Per-tag IVFs (S6): 1,326 tags with >= 10,000 items | 500 s | 231 MB |
| FAISS `IVF16384,SQ8` (WSL, 2 vCPU): signatures, training on 1,048,576 vectors, adding 10M | 49 s + 819 s + 680 s | |

## Not measured

Range filters (M5), the official msturing streaming runbook (exp4), M6 (30M runbook, ACORN-γ), the
1M-slice curves, tombstone drift, exp8 (Azure), exp6 at 50 and 100 queries/s. FAISS has no per-bin
breakdown (its harness times the whole batch).

## Detailed tables (generated)

The oracle rows labelled 2 threads reuse the 4-thread latencies and are not meaningful.

<!-- generated by scripts/gen_numbers.py from results/*.jsonl; do not edit by hand -->

Population weights (private query set bin shares, `results/m0_data.jsonl`): <0.01% 0.242, 0.01-0.1% 0.227, 0.1-1% 0.230, 1-10% 0.244, >10% 0.057

### exp1 / exp2: strategies on yfcc-10M, 10k stratified private queries (`results/exp1_10m.jsonl`)

| config | threads | recall@10 (sample) | QPS (sample) | recall@10 (population-weighted) | QPS (population-weighted) | p50 / p99 latency (us) |
|---|---|---|---|---|---|---|
| `S0` | 4 | 1.0000 | 60.2 | 1.0000 | 113.2 | 8,389 / 327,233 |
| `S1:safety=2,ef=100` | 4 | 0.4423 | 121.6 | 0.3645 | 105.4 | 30,470 / 118,165 |
| `S2:ef=16` | 4 | 0.9603 | 470.3 | 0.9713 | 535.4 | 7,900 / 33,225 |
| `S2:ef=32` | 4 | 0.9803 | 415.2 | 0.9861 | 447.1 | 8,813 / 36,904 |
| `S2:ef=64` | 4 | 0.9901 | 323.4 | 0.9933 | 335.3 | 10,537 / 51,276 |
| `S2:ef=128` | 4 | 0.9951 | 259.3 | 0.9968 | 262.0 | 12,242 / 71,197 |
| `S2:ef=256` | 4 | 0.9971 | 201.8 | 0.9981 | 202.0 | 14,633 / 97,634 |
| `S3:ef=32,threshold=60` | 4 | 0.8559 | 520.2 | 0.8620 | 645.9 | 5,336 / 27,721 |
| `S3:ef=128,threshold=60` | 4 | 0.9254 | 407.6 | 0.9245 | 480.8 | 8,543 / 29,470 |
| `S3:ef=512,threshold=60` | 4 | 0.9591 | 279.9 | 0.9569 | 328.3 | 12,865 / 40,282 |
| `S4:c=4096,nprobe=16` | 4 | 0.7403 | 1,903.5 | 0.7093 | 2,333.0 | 1,645 / 8,375 |
| `S4:c=4096,nprobe=64` | 4 | 0.8827 | 738.6 | 0.8637 | 965.3 | 2,953 / 24,241 |
| `S4:c=4096,nprobe=256` | 4 | 0.9630 | 277.6 | 0.9560 | 449.0 | 4,344 / 73,593 |
| `S4:c=4096,nprobe=512` | 4 | 0.9841 | 178.9 | 0.9810 | 324.6 | 5,817 / 132,334 |
| `S4:c=16384,nprobe=64` | 4 | 0.7867 | 812.1 | 0.7564 | 928.0 | 4,219 / 14,530 |
| `S4:c=16384,nprobe=256` | 4 | 0.9042 | 431.5 | 0.8874 | 537.8 | 5,138 / 34,284 |
| `S4:c=16384,nprobe=1024` | 4 | 0.9698 | 184.4 | 0.9639 | 280.6 | 8,244 / 96,969 |
| `S6:ef=2000` | 4 | 0.7915 | 1,987.9 | 0.8106 | 1,970.7 | 1,383 / 13,088 |
| `S6:ef=8000` | 4 | 0.8771 | 1,217.9 | 0.8760 | 1,303.7 | 3,754 / 13,082 |
| `S6:ef=32000` | 4 | 0.9072 | 492.9 | 0.8960 | 578.2 | 8,586 / 21,900 |
| `S0` | 2 | 1.0000 | 38.8 | 1.0000 | 72.7 | 6,513 / 251,942 |
| `S2:ef=16` | 2 | 0.9603 | 268.0 | 0.9713 | 304.3 | 6,432 / 24,578 |
| `S2:ef=32` | 2 | 0.9803 | 224.6 | 0.9861 | 239.2 | 9,898 / 31,247 |
| `S4:c=16384,nprobe=256` | 2 | 0.9042 | 265.0 | 0.8874 | 329.1 | 4,168 / 26,664 |
| `S4:c=4096,nprobe=256` | 2 | 0.9630 | 189.4 | 0.9560 | 312.1 | 3,229 / 55,454 |
| `S6:ef=32000` | 2 | 0.9072 | 274.8 | 0.8960 | 321.9 | 7,832 / 15,126 |

Best QPS at recall@10 >= 0.90, per strategy:

| strategy | config | threads | recall@10 | QPS (sample) |
|---|---|---|---|---|
| S0 | `S0` | 2 | 1.0000 | 38.8 |
| S0 | `S0` | 4 | 1.0000 | 60.2 |
| S2 | `S2:ef=16` | 2 | 0.9603 | 268.0 |
| S2 | `S2:ef=16` | 4 | 0.9603 | 470.3 |
| S3 | `S3:ef=128,threshold=60` | 4 | 0.9254 | 407.6 |
| S4 | `S4:c=16384,nprobe=256` | 2 | 0.9042 | 265.0 |
| S4 | `S4:c=16384,nprobe=256` | 4 | 0.9042 | 431.5 |
| S6 | `S6:ef=32000` | 2 | 0.9072 | 274.8 |
| S6 | `S6:ef=32000` | 4 | 0.9072 | 492.9 |

By selectivity bin (exp2): best throughput at bin recall@10 >= 0.90 (QPS equivalent = threads / mean latency):

| strategy | <0.01% | 0.01-0.1% | 0.1-1% | 1-10% | >10% |
|---|---|---|---|---|---|
| S0 | 15,238 (`S0`) | 2,670 (`S0`) | 419 (`S0`) | 52 (`S0`) | 16 (`S0`) |
| S1 | never 0.9 | never 0.9 | never 0.9 | 574 (`safety=2,ef=100`) | 1,377 (`safety=2,ef=100`) |
| S2 | 747 (`ef=16`) | 1,213 (`ef=32`) | 332 (`ef=16`) | 543 (`ef=16`) | 296 (`ef=32`) |
| S3 | 1,279 (`ef=512,threshold=60`) | 2,006 (`ef=32,threshold=60`) | 332 (`ef=512,threshold=60`) | 213 (`ef=512,threshold=60`) | 216 (`ef=128,threshold=60`) |
| S4 | 1,661 (`c=4096,nprobe=512`) | 1,936 (`c=4096,nprobe=256`) | 1,256 (`c=4096,nprobe=64`) | 623 (`c=16384,nprobe=64`) | 929 (`c=4096,nprobe=16`) |
| S6 | 16,368 (`ef=8000`) | 3,058 (`ef=32000`) | never 0.9 | 986 (`ef=8000`) | 264 (`ef=32000`) |

### Calibration sweep: 10k stratified public queries (`results/exp1_10m_public.jsonl`)

| config | threads | recall@10 (sample) | QPS (sample) | recall@10 (population-weighted) | QPS (population-weighted) | p50 / p99 latency (us) |
|---|---|---|---|---|---|---|
| `S0` | 4 | 1.0000 | 60.3 | 1.0000 | 112.9 | 8,144 / 343,870 |
| `S1:safety=2,ef=100` | 4 | 0.4435 | 133.5 | 0.3655 | 115.0 | 30,271 / 91,119 |
| `S2:ef=16` | 4 | 0.9590 | 496.9 | 0.9700 | 562.6 | 7,542 / 30,835 |
| `S2:ef=32` | 4 | 0.9793 | 381.3 | 0.9853 | 408.9 | 9,131 / 42,535 |
| `S2:ef=64` | 4 | 0.9896 | 310.4 | 0.9928 | 320.7 | 10,773 / 53,532 |
| `S2:ef=128` | 4 | 0.9948 | 241.5 | 0.9965 | 243.2 | 12,882 / 78,259 |
| `S2:ef=256` | 4 | 0.9969 | 179.2 | 0.9979 | 178.3 | 15,804 / 120,261 |
| `S3:ef=32,threshold=60` | 4 | 0.8580 | 450.5 | 0.8638 | 557.1 | 6,097 / 34,452 |
| `S3:ef=128,threshold=60` | 4 | 0.9271 | 374.0 | 0.9259 | 443.3 | 8,793 / 33,982 |
| `S3:ef=512,threshold=60` | 4 | 0.9601 | 263.2 | 0.9577 | 309.9 | 13,209 / 51,452 |
| `S4:c=4096,nprobe=16` | 4 | 0.7363 | 1,719.3 | 0.7046 | 2,145.6 | 1,728 / 11,272 |
| `S4:c=4096,nprobe=64` | 4 | 0.8805 | 735.6 | 0.8611 | 969.0 | 2,870 / 26,366 |
| `S4:c=4096,nprobe=256` | 4 | 0.9623 | 275.8 | 0.9551 | 445.7 | 4,268 / 73,686 |
| `S4:c=4096,nprobe=512` | 4 | 0.9837 | 138.7 | 0.9805 | 252.1 | 7,340 / 194,019 |
| `S4:c=16384,nprobe=64` | 4 | 0.7831 | 679.9 | 0.7518 | 785.5 | 4,801 / 22,326 |
| `S4:c=16384,nprobe=256` | 4 | 0.9004 | 403.9 | 0.8830 | 505.2 | 5,544 / 41,464 |
| `S4:c=16384,nprobe=1024` | 4 | 0.9687 | 167.7 | 0.9627 | 254.3 | 9,223 / 114,814 |
| `S6:ef=2000` | 4 | 0.7938 | 1,936.5 | 0.8134 | 1,916.9 | 1,372 / 13,524 |
| `S6:ef=8000` | 4 | 0.8795 | 1,200.6 | 0.8793 | 1,287.4 | 3,759 / 13,327 |
| `S6:ef=32000` | 4 | 0.9113 | 371.3 | 0.9008 | 436.2 | 11,237 / 34,860 |

Best QPS at recall@10 >= 0.90, per strategy:

| strategy | config | threads | recall@10 | QPS (sample) |
|---|---|---|---|---|
| S0 | `S0` | 4 | 1.0000 | 60.3 |
| S2 | `S2:ef=16` | 4 | 0.9590 | 496.9 |
| S3 | `S3:ef=128,threshold=60` | 4 | 0.9271 | 374.0 |
| S4 | `S4:c=16384,nprobe=256` | 4 | 0.9004 | 403.9 |
| S6 | `S6:ef=32000` | 4 | 0.9113 | 371.3 |

By selectivity bin (exp2): best throughput at bin recall@10 >= 0.90 (QPS equivalent = threads / mean latency):

| strategy | <0.01% | 0.01-0.1% | 0.1-1% | 1-10% | >10% |
|---|---|---|---|---|---|
| S0 | 12,172 (`S0`) | 2,515 (`S0`) | 418 (`S0`) | 52 (`S0`) | 16 (`S0`) |
| S1 | never 0.9 | never 0.9 | never 0.9 | 725 (`safety=2,ef=100`) | 2,970 (`safety=2,ef=100`) |
| S2 | 816 (`ef=16`) | 1,265 (`ef=16`) | 341 (`ef=16`) | 562 (`ef=16`) | 277 (`ef=32`) |
| S3 | 1,245 (`ef=512,threshold=60`) | 1,795 (`ef=32,threshold=60`) | 310 (`ef=512,threshold=60`) | 202 (`ef=512,threshold=60`) | 196 (`ef=128,threshold=60`) |
| S4 | 1,262 (`c=4096,nprobe=512`) | 1,902 (`c=4096,nprobe=256`) | 1,293 (`c=4096,nprobe=64`) | 529 (`c=16384,nprobe=64`) | 808 (`c=4096,nprobe=16`) |
| S6 | 15,923 (`ef=2000`) | 3,005 (`ef=2000`) | never 0.9 | 964 (`ef=8000`) | 198 (`ef=32000`) |

### FAISS filter baseline rerun on the mini PC (`results/m1_faiss.jsonl`)

| run | nprobe | mt_threshold | threads | repeat | recall@10 | QPS | WSL loadavg at start |
|---|---|---|---|---|---|---|---|
| q10k-stratified | 1 | 0.0003 | 2 | 0 | 0.5025 | 56.9 | [1.41, 1.92, 2.44] |
| q10k-stratified | 4 | 0.0003 | 2 | 0 | 0.6599 | 258.5 | [5.38, 3.69, 3.04] |
| q10k-stratified | 16 | 0.0003 | 2 | 0 | 0.8163 | 140.3 | [3.77, 3.47, 2.99] |
| q10k-stratified | 32 | 0.0003 | 2 | 0 | 0.8800 | 115.6 | [2.7, 3.21, 2.93] |
| q10k-stratified | 64 | 0.0003 | 2 | 0 | 0.9277 | 137.0 | [2.18, 2.9, 2.85] |
| q10k-stratified | 96 | 0.0003 | 2 | 0 | 0.9479 | 136.1 | [2.28, 2.74, 2.79] |
| q10k-stratified | 1 | 0.0001 | 2 | 0 | 0.4555 | 206.1 | [2.09, 2.57, 2.73] |
| q10k-stratified | 4 | 0.0001 | 2 | 0 | 0.6194 | 230.9 | [2.11, 2.49, 2.69] |
| q10k-stratified | 16 | 0.0001 | 2 | 0 | 0.7844 | 211.5 | [2.19, 2.46, 2.67] |
| q10k-stratified | 32 | 0.0001 | 2 | 0 | 0.8537 | 178.8 | [2.17, 2.41, 2.64] |
| q10k-stratified | 64 | 0.0001 | 2 | 0 | 0.9073 | 169.0 | [2.1, 2.34, 2.6] |
| q10k-stratified | 96 | 0.0001 | 2 | 0 | 0.9313 | 85.7 | [2.2, 2.31, 2.58] |
| q10k-stratified | 1 | 0.01 | 2 | 0 | 0.7133 | 32.0 | [4.15, 2.82, 2.72] |
| q10k-stratified | 4 | 0.01 | 2 | 0 | 0.8209 | 111.1 | [4.22, 4.44, 3.51] |
| q10k-stratified | 16 | 0.01 | 2 | 0 | 0.9178 | 95.2 | [3.14, 3.95, 3.41] |
| q10k-stratified | 32 | 0.01 | 2 | 0 | 0.9523 | 83.1 | [2.4, 3.44, 3.28] |
| q10k-stratified | 64 | 0.01 | 2 | 0 | 0.9742 | 76.4 | [2.32, 3.03, 3.14] |
| q10k-stratified | 96 | 0.01 | 2 | 0 | 0.9821 | 77.6 | [2.19, 2.72, 3.01] |
| q10k-stratified-quiet-rerun | 32 | 0.0001 | 2 | 0 | 0.8537 | 143.2 | [0.69, 0.21, 0.29] |
| q10k-stratified-quiet-rerun | 32 | 0.0001 | 2 | 1 | 0.8537 | 196.9 | [1.69, 0.62, 0.43] |
| q10k-stratified-quiet-rerun | 64 | 0.0001 | 2 | 0 | 0.9073 | 210.1 | [1.98, 0.85, 0.52] |
| q10k-stratified-quiet-rerun | 64 | 0.0001 | 2 | 1 | 0.9073 | 182.8 | [2.2, 1.09, 0.62] |
| q10k-stratified-quiet-rerun | 96 | 0.0001 | 2 | 0 | 0.9313 | 190.9 | [2.06, 1.22, 0.69] |
| q10k-stratified-quiet-rerun | 96 | 0.0001 | 2 | 1 | 0.9313 | 186.9 | [2.1, 1.36, 0.77] |
| q10k-stratified-quiet-rerun | 32 | 0.0003 | 2 | 0 | 0.8800 | 213.2 | [2.05, 1.45, 0.84] |
| q10k-stratified-quiet-rerun | 32 | 0.0003 | 2 | 1 | 0.8800 | 235.9 | [2.19, 1.56, 0.9] |
| q10k-stratified-quiet-rerun | 64 | 0.0003 | 2 | 0 | 0.9277 | 182.1 | [2.15, 1.64, 0.96] |
| q10k-stratified-quiet-rerun | 64 | 0.0003 | 2 | 1 | 0.9277 | 155.4 | [2.22, 1.75, 1.04] |
| q10k-stratified-quiet-rerun | 96 | 0.0003 | 2 | 0 | 0.9479 | 166.6 | [2.27, 1.83, 1.11] |
| q10k-stratified-quiet-rerun | 96 | 0.0003 | 2 | 1 | 0.9479 | 161.6 | [2.1, 1.85, 1.17] |
| q10k-stratified-quiet-rerun | 32 | 0.01 | 2 | 0 | 0.9523 | 107.2 | [2.23, 1.93, 1.24] |
| q10k-stratified-quiet-rerun | 32 | 0.01 | 2 | 1 | 0.9523 | 112.9 | [2.26, 2.01, 1.33] |
| q10k-stratified-quiet-rerun | 64 | 0.01 | 2 | 0 | 0.9742 | 97.7 | [2.1, 2.01, 1.4] |
| q10k-stratified-quiet-rerun | 64 | 0.01 | 2 | 1 | 0.9742 | 91.9 | [2.14, 2.03, 1.47] |
| q10k-stratified-quiet-rerun | 96 | 0.01 | 2 | 0 | 0.9821 | 71.0 | [2.4, 2.13, 1.57] |
| q10k-stratified-quiet-rerun | 96 | 0.01 | 2 | 1 | 0.9821 | 66.5 | [2.76, 2.3, 1.71] |

Highest FAISS QPS at recall@10 >= 0.90 across all runs: **210.1** (nprobe 64, mt_threshold 0.0001, recall 0.9073, run `q10k-stratified-quiet-rerun` repeat 0).

### Planner (exp3, `results/m3_exp3.jsonl`)

| planner | setting | threads / machine label | recall@10 | QPS | recall@10, QPS (population-weighted) | QPS from latencies | notes |
|---|---|---|---|---|---|---|---|
| fitted (lagrange) | knob 1991 | 4 / minipc | 0.9038 | 2,152.0 | 0.9017, 2,240.1 | 2,163.1 | mix {'S1:safety=2,ef=100': 2282, 'S4:c=4096,nprobe=16': 1182, 'S4:c=4096,nprobe=64': 1475, 'S6:ef=2000': 4268, 'S6:ef=8000': 793}, overhead mean 100.7 us, p99 1396.7 us |
| fitted (lagrange) | knob 3981 (calibrated) | 4 / minipc | 0.9123 | 2,945.3 | 0.9117, 2,928.3 | 2,954.5 | mix {'S1:safety=2,ef=100': 2405, 'S4:c=4096,nprobe=16': 987, 'S4:c=4096,nprobe=64': 1455, 'S6:ef=2000': 4145, 'S6:ef=8000': 1008}, overhead mean 77.4 us, p99 576.8 us |
| fitted (lagrange) | knob 7962 | 4 / minipc | 0.9232 | 2,925.2 | 0.9244, 2,880.1 | 2,933.2 | mix {'S1:safety=2,ef=100': 2533, 'S4:c=4096,nprobe=16': 737, 'S4:c=4096,nprobe=64': 1426, 'S6:ef=2000': 3984, 'S6:ef=8000': 1320}, overhead mean 74.9 us, p99 531.8 us |
| FAISS rule in FacetIndex | mt 0.0001, `S4:c=16384,nprobe=64` | 4 / minipc | 0.9110 | 943.0 | 0.9047, 1,114.4 | | |
| FAISS rule in FacetIndex | mt 0.0001, `S4:c=16384,nprobe=256` | 4 / minipc | 0.9746 | 410.4 | 0.9718, 526.2 | | |
| FAISS rule in FacetIndex | mt 0.0001, `S4:c=16384,nprobe=1024` | 4 / minipc | 0.9960 | 180.6 | 0.9955, 291.9 | | |
| FAISS rule in FacetIndex | mt 0.0003, `S4:c=16384,nprobe=64` | 4 / minipc | 0.9315 | 839.0 | 0.9280, 997.9 | | |
| FAISS rule in FacetIndex | mt 0.0003, `S4:c=16384,nprobe=256` | 4 / minipc | 0.9836 | 420.9 | 0.9820, 543.5 | | |
| FAISS rule in FacetIndex | mt 0.0003, `S4:c=16384,nprobe=1024` | 4 / minipc | 0.9981 | 207.7 | 0.9979, 338.5 | | |
| FAISS rule in FacetIndex | mt 0.001, `S4:c=16384,nprobe=64` | 4 / minipc | 0.9518 | 1,018.1 | 0.9511, 1,210.1 | | |
| FAISS rule in FacetIndex | mt 0.001, `S4:c=16384,nprobe=256` | 4 / minipc | 0.9900 | 476.1 | 0.9893, 613.7 | | |
| FAISS rule in FacetIndex | mt 0.001, `S4:c=16384,nprobe=1024` | 4 / minipc | 0.9991 | 212.7 | 0.9990, 350.4 | | |
| FAISS rule in FacetIndex | mt 0.01, `S4:c=16384,nprobe=64` | 4 / minipc | 0.9768 | 847.9 | 0.9798, 924.1 | | |
| FAISS rule in FacetIndex | mt 0.01, `S4:c=16384,nprobe=256` | 4 / minipc | 0.9962 | 465.6 | 0.9965, 573.3 | | |
| FAISS rule in FacetIndex | mt 0.01, `S4:c=16384,nprobe=1024` | 4 / minipc | 0.9998 | 205.2 | 0.9998, 333.8 | | |
| oracle | pass 0.9 | 4 / minipc | 0.9769 | | | 2,477.8 | {'S0': 883, 'S1': 1737, 'S2': 462, 'S3': 133, 'S4': 2885, 'S6': 3900} |
| oracle | pass 0.8 | 4 / minipc | 0.9529 | | | 2,973.4 | {'S0': 860, 'S1': 1785, 'S2': 421, 'S3': 159, 'S4': 2800, 'S6': 3975} |
| oracle | pass 1.0 | 4 / minipc | 1.0000 | | | 1,799.1 | {'S0': 914, 'S1': 1464, 'S2': 562, 'S3': 107, 'S4': 3011, 'S6': 3942} |
| misroutes | 10000 queries |  / minipc | | | | | differs from oracle 6328, over 2x oracle latency 467, below 0.9 on the query 2036 |
| fitted (lagrange) | knob 3981 (calibrated) | 2 / minipc | 0.9123 | 1,699.2 | 0.9117, 1,705.3 | 1,705.9 | mix {'S1:safety=2,ef=100': 2405, 'S4:c=4096,nprobe=16': 987, 'S4:c=4096,nprobe=64': 1455, 'S6:ef=2000': 4145, 'S6:ef=8000': 1008}, overhead mean 66.3 us, p99 479.8 us |
| FAISS rule in FacetIndex | mt 0.001, `S4:c=16384,nprobe=64` | 2 / minipc | 0.9518 | 644.0 | 0.9511, 758.4 | | |
| FAISS rule in FacetIndex | mt 0.0003, `S4:c=16384,nprobe=64` | 2 / minipc | 0.9315 | 648.9 | 0.9280, 763.2 | | |
| oracle | pass 0.9 | 2 / minipc | 0.9769 | | | 1,238.9 | {'S0': 883, 'S1': 1737, 'S2': 462, 'S3': 133, 'S4': 2885, 'S6': 3900} |
| oracle | pass 0.8 | 2 / minipc | 0.9529 | | | 1,486.7 | {'S0': 860, 'S1': 1785, 'S2': 421, 'S3': 159, 'S4': 2800, 'S6': 3975} |
| oracle | pass 1.0 | 2 / minipc | 1.0000 | | | 899.6 | {'S0': 914, 'S1': 1464, 'S2': 562, 'S3': 107, 'S4': 3011, 'S6': 3942} |
| misroutes | 10000 queries |  / minipc | | | | | differs from oracle 6328, over 2x oracle latency 467, below 0.9 on the query 2036 |
| fitted (lagrange) | knob 3981 (calibrated) | 2 / minipc-pinned-2-cores | 0.9123 | 1,151.4 | 0.9117, 1,231.9 | 1,159.7 | mix {'S1:safety=2,ef=100': 2405, 'S4:c=4096,nprobe=16': 987, 'S4:c=4096,nprobe=64': 1455, 'S6:ef=2000': 4145, 'S6:ef=8000': 1008}, overhead mean 88.9 us, p99 545.9 us |
| FAISS rule in FacetIndex | mt 0.001, `S4:c=16384,nprobe=64` | 2 / minipc-pinned-2-cores | 0.9518 | 646.0 | 0.9511, 763.1 | | |
| oracle | pass 0.9 | 2 / minipc-pinned-2-cores | 0.9769 | | | 1,238.9 | {'S0': 883, 'S1': 1737, 'S2': 462, 'S3': 133, 'S4': 2885, 'S6': 3900} |
| oracle | pass 0.8 | 2 / minipc-pinned-2-cores | 0.9529 | | | 1,486.7 | {'S0': 860, 'S1': 1785, 'S2': 421, 'S3': 159, 'S4': 2800, 'S6': 3975} |
| oracle | pass 1.0 | 2 / minipc-pinned-2-cores | 1.0000 | | | 899.6 | {'S0': 914, 'S1': 1464, 'S2': 562, 'S3': 107, 'S4': 3011, 'S6': 3942} |
| misroutes | 10000 queries |  / minipc-pinned-2-cores | | | | | differs from oracle 6328, over 2x oracle latency 467, below 0.9 on the query 2036 |

### exp5 / exp9: constructed filtered streaming workload on YFCC (`results/exp5.jsonl`)

| exp | mode | via | SetAttrs/s offered | applied/s | backlog at end | drain s | attr apply lag p50/p99 (us) | Lucene visibility p50/p99 (us) | last-checkpoint recall | query p99 (us) | merge s/min |
|---|---|---|---|---|---|---|---|---|---|---|---|
| exp5 | A2 | kafka | 1,000 | 999 | 0 | 0.0 | 134742015/213778431 | 51740671/235536383 | S4 0.771, S2 0.994 | S2 247595007, S4 242483199 | 0.2 |
| exp5 | A1 | kafka | 1,000 | 1,000 | 0 | 0.0 | 16447/1129471 | 760831/2340863 | S4 0.792, S2 0.993 | S2 1136639, S4 1091583 | 23.8 |
| exp9 | A3 | direct | 10,000 | 9,999 | 0 | 0.0 | 0/0 | 652799/1498111 | S4 0.794, S2 0.991 | S2 252543, S4 240383 | 1.7 |
| exp5 | A3 | kafka | 100 | 100 | 0 | 0.0 | 17311/20054015 | 821759/19447807 | S4 0.794, S2 0.991 | S2 7598079, S4 7946239 | 1.8 |
| exp5 | A3 | kafka | 1,000 | 1,000 | 0 | 0.0 | 16007/5677055 | 697855/5599231 | S4 0.792, S2 0.991 | S2 985599, S4 1283071 | 6.5 |
| exp5 | A3 | kafka | 10,000 | 10,000 | 0 | 0.0 | 16687/2603007 | 753151/3037183 | S4 0.794, S2 0.991 | S2 1327103, S4 1577983 | 2.3 |

### exp6: mixed workload (`results/exp6.jsonl`)

| exp | mode | via | SetAttrs/s offered | applied/s | backlog at end | drain s | attr apply lag p50/p99 (us) | Lucene visibility p50/p99 (us) | last-checkpoint recall | query p99 (us) | merge s/min |
|---|---|---|---|---|---|---|---|---|---|---|---|
| exp6 | A3 | kafka | 1,000 | 1,000 | 0 | 0.0 | 52297727/194379775 | 27443199/219283455 | P 0.894, S4 0.760, S2 0.993 | S2 127991807, S4 128122879, P 127991807 | 4.0 |
