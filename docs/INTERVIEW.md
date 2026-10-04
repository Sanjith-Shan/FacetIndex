# FacetIndex: questions to answer cold

Measured figures come from `NUMBERS.md`, which names the results file of each one. Section 2 holds
the numbers; section 1 is the reasoning, which does not change with them.

## 1. Mechanisms

**Why does post-filtering lose recall as filters get selective?** HNSW returns the k' nearest
vectors regardless of the predicate; if a fraction s of items match, about s·k' of those survive, so
k' has to grow like k/s. At s = 0.01% that is 100,000 candidates for k = 10, and the graph search
cost grows with k'. With a fetch cap, recall collapses once k/s passes the cap. S1 measures this.

**What does Lucene do with a filter on an HNSW query?** `AbstractKnnVectorQuery` materialises the
filter per segment and reads its cost (matching docs). If the cost is at most the per-leaf k, it runs
an exact scan. Otherwise it runs the graph search with a visit limit of cost + 1, keeping only
filter-passing nodes as results but still traversing through non-matching ones; if the limit is hit
before k results are found it falls back to the exact scan. That fallback is why S2 stays accurate
at very low selectivity: it quietly turns into brute force.

**What does the ACORN-1 strategy change?** With `KnnSearchStrategy.Hnsw(threshold)` and a filter that
passes fewer than threshold percent of the graph, Lucene's `FilteredHnswGraphSearcher` only scores
and explores filter-passing nodes and, when a node's immediate neighbourhood is mostly filtered out,
looks at neighbours of neighbours so the predicate subgraph stays connected. It visits far fewer
nodes, so it is faster at the same beam, but at the same beam it can miss neighbours that plain
filtered traversal would reach through non-matching nodes. In Lucene 10.5.1 it is off by default (the
10.x backport set the default threshold to 0 because of that recall difference; main uses 60).

**Why is IVF with bitmap intersection cheap to update, and what does it pay?** A new item costs one
nearest-centroid search (C distance computations) plus one bitmap add per cluster and per tag; a
delete is bitmap removes. No graph is touched, so nothing has to be re-linked or merged. It pays in
recall: true neighbours that live in clusters outside the nprobe probed ones are never seen, and
under a selective filter each probed cluster yields only the matching fraction, so reaching 0.9 needs
many more probes than unfiltered search. Fewer, larger clusters (4,096) need fewer probes per unit
of recall than 16,384 smaller ones but scan more vectors per probe.

**How does the planner know selectivity, and why exact for tags?** Each tag is a RoaringBitmap, so
one tag's match count is its cardinality and two tags' is `andCardinality`, both microseconds. Ranges
would use a point-count estimate combined with the tag count under independence, which can be badly
wrong when attributes correlate (range predicates are implemented but were not measured in this
run). A wrong estimate sends a query to a strategy outside the regime it was calibrated for, which
shows up as a misroute against the oracle.

**Why calibrate on public queries and score on private?** The cost model is fitted to per-query
measurements; scoring on the same queries would reward memorising them. The private set is a fresh
draw from the same distribution, which is what the competition scored on too.

**Why does an attribute update re-insert the vector with terms but not with doc values or the
external store?** Lucene documents are immutable: changing an indexed term means `updateDocument`,
which deletes the old document and indexes a new one, and indexing a document with a vector inserts
that vector into the in-memory HNSW graph of the new segment (and again at every merge). Doc values
support in-place updates (`updateBinaryDocValue`) written as a new generation of the field, so the
vector is untouched, but the filter becomes a scan of every document's doc value. The external store
changes a bitmap and a row array in memory; Lucene never sees it.

**What happens to deleted nodes in Lucene's HNSW until a merge?** They stay in the graph and are still
traversed, but are excluded from results through live docs; only a merge that rewrites the segment
removes them. As the deleted fraction grows, more visits are wasted and recall at a fixed beam drops.
`TieredMergePolicy.deletesPctAllowed` bounds how many can accumulate.

**Why is the streaming metric averaged over checkpoints, and what was the organizers' bug?** Recall
after every insert and delete batch is what a live index must sustain; recall at the start is easy.
More than six months after the competition the organizers found a caching error that made the
reported recall the value at the first search checkpoint instead of the average over the runbook;
the corrected table changed the order (puck fell from 0.985 to 0.0921; pyanns 0.8865 led).

**Why is a 1M slice not comparable to the 10M leaderboard?** Ground truth changes, and every
strategy's cost depends on the absolute number of matching items, not the fraction: brute force over
the matches is ten times cheaper on a 10% slice while graph search is not, so crossover points move
and a planner calibrated on 1M is wrong on 10M. Leaderboard QPS is on 10M on different hardware.

**How was p99 measured without coordinated omission?** An open-loop generator issues query i at
start + i/rate regardless of earlier queries and measures latency from that intended send time, so a
slow query's queueing delay is charged to the queries behind it. The generator parks until 16 ms
before each due time and then spins, because Windows' timer tick would otherwise add up to 15.6 ms.

**Why Kafka instead of an in-process queue?** The update path needs a durable, replayable log with
offsets: a consumer that crashes resumes from its last committed offset, and the applier's
(id, version) check makes the replay idempotent (`KafkaCrashReplayTest` kills a consumer mid-batch
and checks the final state). An in-process twin feeds the same applier so the broker's cost can be
measured (exp9).

**How is this different from HybridSearch?** HybridSearch is a text retrieval engine with its own
DiskANN-style graph in C++. FacetIndex builds no new ANN index: it is the constrained, live-catalog
retrieval layer on Lucene, choosing how to combine a vector query with attribute predicates while
the catalog changes.

**Three bugs from `BUG_LOG.md` worth telling first.**
1. Page-cache thrash (#7): two copies of 1.92 GB of vectors on a memory-squeezed box made QPS depend
   on configuration order; fixed by reading Lucene's own `.vec` file with a signed int8 kernel.
2. The Vector API species in an instance field (#3): k-means ran at a fraction of a core because C2
   could not constant-fold the species; moving the kernels to Java static finals fixed it.
3. Silently skipped property tests (#2): expression-bodied Kotlin tests returning non-Unit are not
   run by JUnit 5, so the SIMD kernel's property tests had not been running at all.

## 2. Measured answers

All on yfcc-10M, the 10k stratified private sample, mini PC, 4 threads unless stated.

- **Post-filter recall loss.** S1 (k' = k / selectivity x 2, capped at 10,000) reached 0.442 overall
  (`results/exp1_10m.jsonl`). It never reaches 0.9 below 1% selectivity, but above 10% it is the
  fastest strategy at 0.9 (1,377 QPS equivalent), because a tenth of the corpus matches.
- **Lucene default vs ACORN-1.** S2 at beam 16: 0.960 at 470 QPS; S3 (threshold 60) at beam 128:
  0.925 at 408 QPS. At equal beam S3 is faster and less accurate (beam 32: S3 0.856 at 520 vs S2
  0.980 at 415). S3 was fastest of the graph paths in the 0.01 to 0.1% bin (2,006 vs S2's 1,213).
- **IVF trade.** S4 with 4,096 cells: 0.883 at nprobe 64 (739 QPS), 0.963 at 256 (278 QPS); with
  16,384 cells: 0.904 at 256 (432 QPS). It is the best strategy for 0.1 to 1% selectivity
  (1,256 QPS equivalent).
- **Per-tag IVF (S6).** 0.907 at 493 QPS with a 32,000-candidate target, the best single
  configuration at 0.9; 1,326 tags got their own IVF, 231 MB, built in 500 s
  (`results/m2_build.jsonl`).
- **Planner.** Calibrated on public, scored on private: 0.912 at 2,945 QPS, 6.0x the best single
  configuration and 2.9x the FAISS rule rebuilt in FacetIndex (0.952 at 1,018). Mean decision cost
  77 us. Misroutes against the 9-of-10 oracle: 63% of choices differ, 20% of queries fall below 0.9
  on that query, 4.7% cost more than twice the oracle's latency (`results/m3_exp3.jsonl`).
- **Against FAISS on the same box.** FAISS's best at 0.9 on 2 vCPUs: 210.1 QPS; the planner pinned
  to 2 cores: 1,151.4 QPS, 5.5x (`results/m1_faiss.jsonl`, `results/m3_exp3.jsonl`). Caveats: FAISS
  trained on a 1M sample, not its default 4.2M; its first run shared WSL with another job.
- **Build costs.** 10M HNSW (M 16, beam 100): 85 min to add 9M documents and 3 h to force-merge them
  under memory pressure, then 3 min and 39 min for the last 1M (`results/m2_build.jsonl`). k-means
  with 16,384 cells: 57 min training on a 1M sample and 37 min assigning 10M.
- **Attribute updates three ways** (constructed workload: 9M-item start, 100 inserts/s and 100
  deletes/s, SetAttrs at the stated rate, 150 s through Kafka; `results/exp5.jsonl`):
  - A3 external store kept up at 100, 1,000 and 10,000 SetAttrs/s (9,999.7/s applied at 10,000, no
    backlog). Produce-to-applied, which for A3 is produce-to-visible, had a median of 16 to 17 ms;
    its p99 was 2.6 s at 10,000/s but 20 s at 100/s, so the tail is not rate-driven: the one consumer
    thread also applies the inserts and deletes to Lucene and stalls behind its writes.
  - A1 terms kept up at 1,000/s but merged 23.8 s per minute (A3: 1.8 to 6.5), because every update
    re-adds the document and re-inserts its vector into the graph.
  - A2 doc values at 1,000/s pushed Lucene's write-to-visible p99 to 235 s and grew the index from
    2.97 to 3.37 GB in 150 s: each refresh writes a new generation of the doc-value field for the
    whole 9M-document segment, and its filter scans every document. The box was saturated in that run.
  - Filtered recall at the checkpoints stayed flat during the updates: S2 0.991 to 0.993, S4 (nprobe
    32) 0.77 to 0.79, against exact answers over the live set.
- **What Kafka costs (exp9).** The same A3 run at 10,000/s through the in-process twin: query p99
  0.25 s (S2) against 1.33 s through Kafka; the broker and consumer took CPU from the queries.
- **Mixed-load p99 (exp6).** Not clean: with the planner, S2 and S4 sharing one open-loop pool at 10
  and 20 queries/s under 1,000 updates/s, medians were 46 s and 36 s (`results/exp6.jsonl`); S2 and
  S4 alone at 10 queries/s had an 18.7 ms median in exp5. The planner's post-filter picks (fetch up to
  10,000) are calibrated on a static index and too expensive under load. What shared the cores: the
  query pool, the consumer, the NRT refresher and merges in one JVM, and the Kafka broker in WSL.
