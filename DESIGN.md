# FacetIndex design

## 1. Sources and credit

FacetIndex rebuilds published ideas inside one Lucene-based service and measures them. Everything
below is someone else's idea unless the section says otherwise.

| Source | What it gave this project |
|---|---|
| Simhadri et al., *Results of the Big ANN: NeurIPS'23 competition*, arXiv 2409.17424 | The filtered and streaming track definitions, datasets, metrics, leaderboards, the FAISS baseline, and the corrected streaming results (the original scores measured recall at the first checkpoint only, because of a caching bug found months after the competition) |
| `harsha-simhadri/big-ann-benchmarks`, `neurips23/filter/faiss` (README, `faiss.py`, `bow_id_selector.swig`, `config.yaml`) | The FAISS baseline itself, which is a two-way planner: brute force over the matching items below a selectivity threshold (`mt_threshold`), `IVF16384,SQ8` with a bag-of-words ID selector and binary signatures above it. Rerun unchanged on the mini PC as the bar |
| `neurips23/filter/parlayivf` and the competition report's description of ParlayANN's IVF² (Manohar et al., *ParlayANN*, PPoPP 2024) | Index by tag first: per-tag structures for common tags, flat storage for rare tags, strategy by tag cardinality. S4 and S6 are this idea inside Lucene's segment model |
| Patel, Kraft, Guestrin, Zaharia, *ACORN*, SIGMOD 2024 (PACMMOD 2(3), arXiv 2403.04871) | Predicate-subgraph search with two-hop expansion (ACORN-1) and the denser ACORN-γ construction (M·γ candidate edges, compressed to M_β). S5 follows the construction idea over Lucene's graph builder |
| Lucene PR #14160 (filtered HNSW in the style of ACORN-1, `KnnSearchStrategy.Hnsw(filteredSearchThreshold)`, Lucene 10.2) and Benjamin Trent, *Filtered HNSW search, fast mode* (Elastic Search Labs, 2025-02-27) | S3 is Lucene's own code. In Lucene 10.5.1 `KnnSearchStrategy.DEFAULT_FILTERED_SEARCH_THRESHOLD` is 0, so the filtered searcher is off unless a threshold is passed; S2 is the default path, S3 passes a threshold |
| Lucene `AbstractKnnVectorQuery` (10.5.1 source) | The stock behaviour S2 measures: exact search when the filter's cost is at most the per-leaf k, otherwise graph search with a visit limit of cost + 1, falling back to exact search when the limit is hit |
| Gollapudi et al., *Filtered-DiskANN*, WWW 2023 | Label-aware graph construction (FilteredVamana, StitchedVamana). Read for context, deliberately not built |
| Gupta et al., *CAPS*, arXiv 2308.15014 | A partition index plus an attribute frequency tree at a fraction of a graph index's size: the IVF-as-filter family S4 belongs to |
| Qdrant, *Filtered Vector Search: What ACORN Fixes, and What Fixes ACORN* (2026) and its query-planning docs (`full_scan_threshold`, payload-index cardinality estimates) | Per-query planning from a cardinality estimate across full scan, payload-index retrieval, filterable HNSW and ACORN |
| Weaviate, *How we speed up filtered vector search with ACORN* (2024) | The correlation finding (filters uncorrelated with the query vector hurt graph search most) and a flat-search cutoff |
| FAISS wiki, *Setting search parameters for one query* | `IDSelector` / `IDSelectorBitmap` filtering inside the IVF scan |
| Iff et al., *Benchmarking Filtered ANN Search on Transformer-based Embedding Vectors*, arXiv 2507.21989 (the `arxiv-for-fanns` dataset); Shi, Cai, Zheng, arXiv 2509.07789; Engels et al., *ANN Search with Window Filters*, ICML 2024; Zuo et al., *SeRF*, SIGMOD 2024; Xu et al., *iRangeGraph*, SIGMOD 2025 | Range filters and the methods specialised for them. The `arxiv-for-fanns` data carries no license on Hugging Face or GitHub, so it was not downloaded (see `README.md`) |
| Abdool et al., *Applying Embedding-Based Retrieval to Airbnb Search*, arXiv 2601.06873, sections 5.3 and 5.4 | One prior work on IVF over HNSW under real-time attribute updates, with the cluster id applied as an ordinary Lucene filter |
| Elastic DiskBBQ (Elasticsearch 9.2, `ES920DiskBBQVectorsFormat`) and Lucene draft PRs #16567 (IVFaster) and #16709 (SegmentIVF) | IVF built as a Lucene codec. S4 differs on purpose: it is a query-time structure beside Lucene (centroids plus RoaringBitmaps over row ids), so the same cluster lists can be intersected with tag bitmaps and updated in place without a merge |
| RoaringBitmap, HdrHistogram, Apache Kafka, gRPC, JMH | Libraries used as published |
| An earlier project of mine | The index wrapper (merge timer, update-to-visible lag sampler, scheduled NRT refresher), the never-cached external-state query, the per-row seqlock store, the open-loop load generator and the update replayer were ported from it and renamed; none of its data or numbers appear here |

**What is Lucene's and what is this project's.** Lucene's HNSW graph, its filtered traversal (the
default and the ACORN-1 style searcher), its exact-search fallback, NRT search, points, doc values
and merges are Lucene's. This project wrote: the SIMD pre-filter kernel and S0, k-means and the IVF
with posting-list intersection (S4, and S4-L as its pure-Lucene twin), the per-tag sub-indexes (S6),
the ACORN-γ style construction experiment (S5), the cost model and planner, the external attribute
store (A3) and the two Lucene-side attribute paths (A1, A2), the Kafka update path with its
idempotent consumer, and every measurement and harness.

## 2. Shape of the system

```
 gRPC Search(vector, predicate, k, mode?)      Upsert / Delete / SetAttrs (gRPC or Kafka)
                     |                                         |
          +----------v-----------+                    +--------v---------+
          | Planner              |                    | Applier          |  one writer, idempotent
          |  exact tag counts -> |                    |  by (id, version)|  by (id, version)
          |  cost model -> pick  |                    +--------+---------+
          +----------+-----------+                             |
     +--------+------+-----+--------+--------+                 |
     S0       S1     S2    S3       S4/S4-L  S6                 |
     \________\______\_____\________\________\__________________/
       one Lucene index (sorted by row, NRT SearcherManager) + attribute store (A3) + IVF lists
```

Row ids are the dataset's own ids everywhere: ground truth, bitmaps, Lucene's `row` doc value and
the IVF lists. The Lucene index is sorted by `row`, so inside a segment docids increase with row id
and a force-merged index with no deletes has docid equal to row. `LeafRows` caches each segment's
docid-to-row array and maps rows back by binary search.

## 3. The strategies

| | Strategy | What runs |
|---|---|---|
| S0 | Pre-filter brute force | Iterate the predicate's rows (one tag bitmap, or the `and` of two) and score each with the uint8 SIMD kernel into a bounded heap. Exact |
| S1 | Post-filter HNSW | Unfiltered Lucene HNSW for k' = k / selectivity × safety (capped), then drop rows failing the predicate |
| S2 | Lucene filtered HNSW (default) | `KnnByteVectorQuery(vec, q, ef, filter, Hnsw(0))`, top k of ef |
| S3 | Lucene ACORN-1 style | The same with `Hnsw(threshold)`, so Lucene's `FilteredHnswGraphSearcher` runs when the filter passes fewer than threshold percent of the graph |
| S4 | IVF with posting-list intersection | nprobe nearest of C centroids, each probed cluster's bitmap `and` the tag bitmaps, survivors scored exactly. When the predicate's own match set is smaller than the probed clusters, it walks the match set and checks each row's cluster instead |
| S4-L | The S4 plan as a Lucene filter | `IntField.newSetQuery(cluster_C, probes)` conjoined with the tag terms; matches scored with Lucene's vector scorer |
| S6 | Per-tag IVF | Each tag with at least 10,000 items has its own IVF trained on its items; the query's rarest indexed tag supplies cells, other tags are checked against bitmaps |
| S5 | ACORN-γ style construction | A denser Lucene graph (M·γ neighbours) searched with Lucene's predicate-subgraph searcher (M6) |

uint8 vectors are stored in Lucene as `KnnByteVectorField` after flipping the top bit (x xor 0x80,
which maps 0..255 to -128..127 and preserves L2 distances exactly), since Lucene's byte vectors are
signed.

S2 and S3 can take the predicate three ways (`FilterMode`): tag terms (A1), the tag doc value (A2),
or `RowSetQuery`, a never-cached query whose iterator is driven by the attribute store's bitmap for
this query (A3). With A3 the filter's cost is the exact match count, which is what Lucene's
exact-or-graph decision reads.

## 4. The planner

Tag selectivity is exact: one tag is its bitmap's cardinality, two tags are
`RoaringBitmap.andCardinality`. Each candidate configuration (a strategy plus its knob) has a
fitted model of latency and recall@10 as a piecewise-linear function of log10(matching items),
separately for one-tag and two-tag queries, fitted on per-query measurements from the public query
set. Per query the planner minimises predicted latency minus λ × predicted recall; λ is the single
knob that trades recall for time consistently across queries, chosen on the public set as the
cheapest setting whose predicted mean recall clears 0.9 plus a small margin. It is scored on the
private set. Baselines: the best single configuration, the FAISS baseline's rule (brute force below
`mt_threshold` using its independence estimate, IVF above) rebuilt inside this engine, and an oracle
that knows each private query's cheapest configuration reaching 9 of 10 true neighbours.

## 5. Attribute updates: A1, A2, A3

- **A1, terms.** Tags are indexed `StringField`s. A change is `updateDocument`: delete and re-add the
  document, which re-inserts its vector into the HNSW graph.
- **A2, doc values.** Tags are sorted ints in a `BinaryDocValues` field, changed with
  `updateBinaryDocValue`, which does not touch the vector. Lucene writes doc-value updates as a new
  generation of the whole field for the segment at flush, and a doc-value filter has no inverted
  index, so the filter is a two-phase scan of every document.
- **A3, external store.** Per-tag RoaringBitmaps plus per-row tag arrays outside Lucene, updated in
  place: each row has a seqlock (one writer, readers retry on a torn read) and each tag's bitmap sits
  behind one of 1,024 striped read-write locks held only while a reader intersects. S0, S4 and S6
  read it directly; S2 and S3 see it through `RowSetQuery`, so a change is visible to the next query
  with no refresh.

## 6. The streaming layer

Kafka 4.3.1 in KRaft mode, one broker, topic `catalog-events` with 4 partitions keyed by item id,
Protobuf payloads (`CatalogEvent` wraps the same messages as the gRPC API). One consumer applies
events in partition order and commits offsets after each poll batch. The applier is idempotent by
(id, version): a replay after a crash skips what was already applied, which `KafkaCrashReplayTest`
checks by killing a consumer in the middle of a batch. A `SearcherManager` refreshes every R ms; a
refresh listener records produce-to-visible time for sampled writes. Merges are timed by a merge
scheduler subclass. An in-process twin (`DirectSink`) feeds the same applier without Kafka so the
broker's cost is measured rather than assumed.

Two workloads: the official runbook (`msturing-10M-clustered`, `delete_runbook.yaml`) replayed into
a fresh HNSW index and scored the track's way, and a constructed filtered workload on YFCC
(start from 9M items, stream the rest as inserts, delete at the same rate, flip tags with SetAttrs
at 0 / 100 / 1,000 / 10,000 per second), labelled as constructed wherever it appears.
