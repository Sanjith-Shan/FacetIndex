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

Filled from `NUMBERS.md` below.
