# exp8 runbook: the filtered-track headline on an Azure Standard D8lds v5 (not run)

This is a ready-to-run plan for rerunning exp1's headline numbers on the VM type the NeurIPS'23
competition scored on. It has **not** been run: it costs money and needs approval first. Nothing in
`results/` comes from Azure. If it is run, the numbers are "same VM type, different instance and
date", and they are reported that way.

## What it buys

The mini PC has 4 cores and 14.9 GB; the scoring VM has 8 vCPUs (Xeon Platinum 8370C) and 16 GB.
Every comparison in the repo today is a ratio against the FAISS baseline rerun on the mini PC. On a
D8lds v5 the FAISS baseline and FacetIndex could both be measured on the leaderboard's hardware
class, so absolute QPS could sit next to the leaderboard's (still a different instance and date).

## Cost and time

- VM: Standard D8lds v5 (8 vCPU, 16 GB, local temp SSD). Check the current East US price at
  rental time; the close sibling D8ds v5 listed around $0.45/hour when the spec was written.
- Plan for a 6 to 8 hour session (about $3 to $4 of compute plus a small disk and egress charge):
  data download 15 min, FAISS build about 1 hour, FacetIndex index build about 3 hours on 8 vCPUs
  (the mini PC needed 6 hours on 4 cores under memory pressure), sweeps 1 to 2 hours.
- Delete the resource group at the end.

## Steps

```bash
# 1. Create (Azure CLI on any machine)
az group create -n facetindex-exp8 -l eastus
az vm create -g facetindex-exp8 -n fi-d8lds --image Ubuntu2404 --size Standard_D8lds_v5 \
  --admin-username fi --generate-ssh-keys --os-disk-size-gb 128
ssh fi@<ip>

# 2. Toolchain and data (the local temp disk is at /mnt)
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless git python3-venv docker.io
export FACETINDEX_DATA=/mnt/fi && sudo mkdir -p $FACETINDEX_DATA && sudo chown fi $FACETINDEX_DATA
git clone https://github.com/Sanjith-Shan/FacetIndex && cd FacetIndex
python3 scripts/download.py yfcc
git clone --depth 1 https://github.com/harsha-simhadri/big-ann-benchmarks $FACETINDEX_DATA/src/big-ann-benchmarks

# 3. FAISS baseline with the official harness (Docker), as the leaderboard ran it
cd $FACETINDEX_DATA/src/big-ann-benchmarks
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements_py3.10.txt
python install.py --neurips23track filter --algorithm faiss
python run.py --neurips23track filter --algorithm faiss --dataset yfcc-10M
python data_export.py --out res.csv      # QPS and recall per query-args row
cd -

# 4. FacetIndex (same commands as on the mini PC, with 8 threads)
./gradlew installDist jar
sed -i 's/--threads 4/--threads 8/; s/--merge-workers 4/--merge-workers 8/' scripts/build_10m.sh
bash scripts/run_experiments.sh build      # IVFs, 10M index, per-tag IVFs
# exp1 private sweep with 8 query threads
sed 's/--threads 4/--threads 8/g' scripts/run_experiments.sh > /tmp/run8.sh && bash /tmp/run8.sh exp1-10m

# 5. Copy results back and tear down
scp fi@<ip>:FacetIndex/results/exp1_10m.jsonl results/exp8_azure_d8lds_v5.jsonl
az group delete -n facetindex-exp8 --yes
```

## How to report it

- Label every line with `machine.label = "azure-d8lds-v5"` (set `FACETINDEX_MACHINE` before running).
- Quote the FAISS baseline from step 3 next to FacetIndex from step 4: same VM type, same date.
- The leaderboard's numbers stay context: "the leaderboard ran on the same VM type in 2023;
  this is a different instance".
