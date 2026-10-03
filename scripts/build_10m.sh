#!/usr/bin/env bash
# Builds the yfcc-10M Lucene index in two steps: rows 0..9M (force-merged, then copied as the base
# snapshot of the constructed filtered streaming workload), then rows 9M..10M appended and merged.
set -e
D="${FACETINDEX_DATA:-C:/SullaPortal/data/facetindex}"
cd "$(dirname "$0")/.."
FI_HEAP=5g ./scripts/fi.sh build-index --data $D/yfcc --name yfcc-10M --index $D/idx/yfcc10m --ivf $D/ivf/yfcc10m_c4096.ivf \
  --from 0 --to 9000000 --threads 4 --merge-workers 4 --ram-mb 1024 --copy-to $D/idx/yfcc9m_base --results results/m2_build.jsonl
FI_HEAP=5g ./scripts/fi.sh build-index --data $D/yfcc --name yfcc-10M --index $D/idx/yfcc10m --ivf $D/ivf/yfcc10m_c4096.ivf \
  --append --from 9000000 --to 10000000 --threads 4 --merge-workers 4 --ram-mb 1024 --results results/m2_build.jsonl
