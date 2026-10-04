# Which filtered vector search strategy wins, at what selectivity, on Lucene?

Every marketplace, store and ad system runs vector search where the query also carries attribute
filters: in stock, this category, ships here, this advertiser's targeting. The NeurIPS'23 Big-ANN
competition made this a track of its own, because neither the graph indexes nor the inverted-file
indexes handle it well across the board, and every vector database since has shipped a different
answer. FacetIndex puts five answers side by side inside one Lucene-based engine, on the track's own
data, and adds a planner that picks per query.

All numbers here come from `results/*.jsonl` (see `NUMBERS.md`). They were measured on a 4-core
AMD Ryzen 3 4300U mini PC with 14.9 GB of RAM, not on the competition's 8-vCPU scoring VM, so no
absolute number here is comparable to the leaderboard; the comparison that is fair is against the
track's FAISS baseline, rerun on the same box with the same queries.

## The data

`yfcc-10M`: 10 million images as 192-dimensional uint8 CLIP vectors, each with a bag of tags from a
200,386-word vocabulary (about 10.8 tags per image). A query is an image vector plus one or two tags,
and a result must carry every query tag. The query set is built so that selectivity spans five
decades: roughly a quarter of the queries match under 0.01% of the items (fewer than 1,000), a
quarter 0.01 to 0.1%, a quarter 0.1 to 1%, a quarter 1 to 10%, and 6% match more than 10%
(`results/m0_data.jsonl`).

## The strategies

- **S0, brute force over the matches.** Intersect the tag bitmaps, score every match with a SIMD
  kernel. Exact; its cost is the number of matches.
- **S1, post-filter.** Ask the graph for k/selectivity candidates and drop the non-matching ones.
- **S2, Lucene's filtered HNSW.** Traverse the graph, keep only matching nodes as results, fall back
  to an exact scan when the filter is tiny or the visit limit runs out.
- **S3, Lucene's ACORN-1 style search.** Traverse only matching nodes, expanding to neighbours of
  neighbours to stay connected.
- **S4, IVF with posting-list intersection.** k-means cells as RoaringBitmaps; probe the nearest
  cells and intersect each with the tag bitmaps.
- **S6, per-tag IVF.** Every tag with at least 10,000 items gets its own small IVF trained on its
  own items (ParlayANN's idea), so a probed cell is all matches.

## Results
