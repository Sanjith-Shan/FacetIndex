#!/usr/bin/env bash
# Builds the FAISS baseline index in bounded memory inside WSL (see faiss_build_chunked.py); arguments pass through.
set -eo pipefail
BASE=/mnt/c/SullaPortal/data/facetindex/wsl
export MAMBA_ROOT_PREFIX=$BASE/mamba
eval "$("$BASE/bin/micromamba" shell hook -s bash)"
micromamba activate "$MAMBA_ROOT_PREFIX/envs/faiss"
export PYTHONUNBUFFERED=1
exec python /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_build_chunked.py "$@" < /dev/null
