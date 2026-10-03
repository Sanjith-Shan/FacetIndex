#!/usr/bin/env bash
# One-time setup of the FAISS filter-track baseline environment inside WSL (Ubuntu 24.04).
# Reproduces neurips23/filter/faiss/Dockerfile without Docker, user-space only (no sudo).
# Usage: wsl -d Ubuntu-24.04 -- bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl_setup.sh
set -euo pipefail

BASE=/mnt/c/SullaPortal/data/facetindex/wsl
export MAMBA_ROOT_PREFIX=$BASE/mamba
ENV=$MAMBA_ROOT_PREFIX/envs/faiss
FBUILD=$BASE/faiss_build
BAB=/mnt/c/SullaPortal/data/facetindex/src/big-ann-benchmarks
MM=$BASE/bin/micromamba

mkdir -p "$BASE/bin" "$FBUILD"
if [ ! -x "$MM" ]; then
  curl -Ls https://micro.mamba.pm/api/micromamba/linux-64/latest | tar -xj -C "$BASE" bin/micromamba
fi

if [ ! -x "$ENV/bin/python" ]; then
  "$MM" create -y -p "$ENV" -c pytorch -c conda-forge \
    python=3.10 'faiss-cpu=1.*' numpy scipy pyyaml h5py psutil requests swig=4.0.2 gxx_linux-64
fi

# faiss from the pytorch channel is wrapped with SWIG 4.0.2 (runtime ABI 4); the selector must use the same SWIG
# or the two modules do not share a type table and faiss.swig_ptr pointers are rejected.
"$MM" install -y -p "$ENV" -c conda-forge swig=4.0.2

eval "$("$MM" shell hook -s bash)"
set +u; micromamba activate "$ENV"; set -u

# Same two commands as the Dockerfile, with CONDA_PREFIX pointing at this env.
cd "$FBUILD"
cp "$BAB/neurips23/filter/faiss/bow_id_selector.swig" .
swig -c++ -python -I"$CONDA_PREFIX/include" -Ifaiss bow_id_selector.swig
if [ -f "$CONDA_PREFIX/lib/libfaiss_avx2.so" ]; then FAISSLIB=$CONDA_PREFIX/lib/libfaiss_avx2.so; else FAISSLIB=$CONDA_PREFIX/lib/libfaiss.so; fi
echo "linking against $FAISSLIB"
x86_64-conda-linux-gnu-g++ -shared -O3 -g -fPIC bow_id_selector_wrap.cxx -o _bow_id_selector.so \
  -I "$(python -c 'import sysconfig; print(sysconfig.get_paths()["include"])')" \
  -I "$CONDA_PREFIX/include" "$FAISSLIB" -Ifaiss -Wl,-rpath,"$CONDA_PREFIX/lib"

PYTHONPATH="$FBUILD" python -c 'import faiss, bow_id_selector; print("faiss", faiss.__version__); print(faiss.IndexFlatL2); print(bow_id_selector.IDSelectorBOW)'
