# FacetIndex

[![ci](https://github.com/Sanjith-Shan/FacetIndex/actions/workflows/ci.yml/badge.svg)](https://github.com/Sanjith-Shan/FacetIndex/actions/workflows/ci.yml)

FacetIndex is a filtered and streaming vector search engine in Kotlin on Apache Lucene 10.5.1: each
query is a vector plus attribute predicates (tags, and numeric ranges), and a planner picks one of
several filter strategies per query from the exact number of matching items, while inserts, deletes
and attribute updates stream in through Kafka. It is measured on the NeurIPS'23 Big-ANN filtered
track (`yfcc-10M`: 10M CLIP image vectors, 192-dim uint8, tags from a 200,386-word vocabulary,
CC BY 4.0) and streaming track (`msturing-10M-clustered`, O-UDA), on a 4-core AMD Ryzen 3 4300U
mini PC with 14.9 GB RAM, not on the competition's 8-vCPU Azure D8lds v5 scoring VM, so its absolute
QPS is never presented as a leaderboard result; the comparison it claims is a ratio against the
track's own FAISS baseline rerun on the same machine. It does not claim a new ANN index: the HNSW
graph and the ACORN-1 filtered traversal (strategies S2 and S3) are Lucene's code. What this project
built is the pre-filter kernel (S0), the IVF with posting-list intersection (S4, S4-L), per-tag
sub-indexes (S6), ACORN-gamma style graph construction (S5), the cost-model planner, the external
attribute store, the Kafka update path, and every measurement. The filtered streaming workload on
YFCC is constructed by this project, not a competition track, and is labelled that way wherever it
appears.

Status: work in progress. See `NUMBERS.md` for every measured figure and the file it came from.

## Layout

| Path | What |
|---|---|
| `src/main/kotlin/facetindex/strategy` | The filter strategies behind one `FilterStrategy` interface |
| `src/main/kotlin/facetindex/attrs` | The external attribute store (per-tag RoaringBitmaps, per-row seqlocks) |
| `src/main/kotlin/facetindex/index` | The Lucene index wrapper, filter queries, NRT lag sampling, merge timing |
| `src/main/kotlin/facetindex/ivf` | k-means (written here) and the IVF cluster bitmaps |
| `src/main/java/facetindex/simd` | uint8 and float L2 kernels on the JDK Vector API |
| `src/main/kotlin/facetindex/cli`, `bench` | Commands that build indexes and write `results/*.jsonl` |
| `scripts/` | Download (with size checks and a disk guard), GT verification, FAISS baseline, mini PC runner |
| `results/` | Summaries of every measurement, each line with machine, load and repeat count |

## Data

Nothing large lives in this repository. `scripts/download.py` fetches the benchmark files into
`$FACETINDEX_DATA` (default `C:/SullaPortal/data/facetindex` on the mini PC), checking each file's
size against the server and refusing to start when free disk would drop under 20 GB.
