#!/usr/bin/env bash
# Run the FAISS filter-track baseline inside WSL with the micromamba env; all arguments go to faiss_baseline.py.
# Example: wsl -d Ubuntu-24.04 -- bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl.sh --queries private --threads 4
set -eo pipefail
BASE=/mnt/c/SullaPortal/data/facetindex/wsl
export MAMBA_ROOT_PREFIX=$BASE/mamba
eval "$("$BASE/bin/micromamba" shell hook -s bash)"
micromamba activate "$MAMBA_ROOT_PREFIX/envs/faiss"
export PYTHONUNBUFFERED=1
# stdin from /dev/null: faiss.py calls pdb.set_trace() if a metadata-first query has < k matches;
# with no stdin pdb quits (BdbQuit) instead of hanging a headless run.
exec python /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_baseline.py "$@" < /dev/null
