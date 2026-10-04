#!/usr/bin/env bash
# The exact commands that produced results/*.jsonl, as phases. Run from the repo root on the mini PC
# after ./gradlew installDist jar. Data under $FACETINDEX_DATA (default C:/SullaPortal/data/facetindex).
#   bash scripts/run_experiments.sh <phase>
set -euo pipefail
D="${FACETINDEX_DATA:-C:/SullaPortal/data/facetindex}"
FI=./scripts/fi.sh
Y=$D/yfcc; Y1=$D/yfcc1m
IVF4=$D/ivf/yfcc10m_c4096.ivf; IVF16=$D/ivf/yfcc10m_c16384.ivf
PT=$D/ivf/yfcc10m_pertag.ivf
# The strategy grid swept on both query sets (public for calibration, private for scoring).
GRID_Q10K="S0;S1:safety=2,ef=100;S2:ef=16|32|64|128|256;S3:ef=32|128|512,threshold=60;S4:c=4096,nprobe=16|64|256|512;S4:c=16384,nprobe=64|256|1024;S6:ef=2000|8000|32000"
Q10K=$D/yfcc_q10k
GRID10="S0;S2:ef=16|32|64|128|256;S3:ef=16|32|64|128|256,threshold=60;S2:ef=64,filter=terms;S4:c=4096,nprobe=16|32|64|128|256|512;S4:c=16384,nprobe=64|128|256|512|1024;S6:ef=2000|4000|8000|16000|32000|64000"
GRID10_SLOW="S4L:c=4096,nprobe=64|256;S1:safety=1|2|4,ef=100"
GRID1="S0;S2:ef=16|32|64|128|256;S3:ef=16|32|64|128|256,threshold=60;S4:c=1024,nprobe=4|8|16|32|64|128;S4L:c=1024,nprobe=32"

case "${1:-}" in
  data)      python scripts/download.py yfcc msturing10m
             python scripts/download_runbook_gt.py --dataset msturing-10M-clustered --runbook delete_runbook.yaml --out msturing10m/delete_runbook_gt ;;
  m0)        $FI stats --data $Y --out results/m0_data.jsonl
             $FI slice --data $Y --out $Y1 --n 1000000 --threads 4
             python scripts/verify_gt.py --data $Y1 --out results/m0_data.jsonl --limit 2000
             python scripts/verify_gt.py --data $Y --name yfcc-10M --out results/m0_data.jsonl --limit 1000 ;;
  build)     FI_HEAP=8g $FI build-ivf --data $Y --name yfcc-10M --clusters 4096 --out $IVF4 --results results/m2_build.jsonl
             FI_HEAP=8g $FI build-ivf --data $Y --name yfcc-10M --clusters 16384 --out $IVF16 --results results/m2_build.jsonl
             bash scripts/build_10m.sh
             FI_HEAP=6g $FI build-pertag --data $Y --name yfcc-10M --out $PT --results results/m2_build.jsonl
             $FI build-ivf --data $Y1 --name yfcc-1M-slice --clusters 1024 --out $D/ivf/yfcc1m_c1024.ivf --results results/m2_build.jsonl
             $FI build-index --data $Y1 --name yfcc-1M-slice --index $D/idx/yfcc1m --ivf $D/ivf/yfcc1m_c1024.ivf --ranges --results results/m2_build.jsonl ;;
  subset)    $FI subset --data $Y --out $Q10K --per-bin 2000 ;;
  exp1-q10k-public)   # calibration data for the planner (ran while the FAISS index built in WSL; see notes in the results)
             FI_HEAP=5g $FI sweep --data $Q10K --name yfcc-10M-q10k --queries public --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT                --strategies "$GRID_Q10K" --threads 4 --warmup 500 --global-warmup 2000 --run q10k --out results/exp1_10m_public.jsonl --experiment exp1_calibration --note "${NOTE:-}" ;;
  exp1-q10k-private)
             FI_HEAP=5g $FI sweep --data $Q10K --name yfcc-10M-q10k --queries private --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT                --strategies "$GRID_Q10K" --threads ${THREADS:-4} --warmup 500 --global-warmup 2000 --run q10k --out results/exp1_10m.jsonl --experiment exp1 --note "${NOTE:-}" ;;
  exp3-q10k) FI_HEAP=5g $FI exp3 --data $Q10K --name yfcc-10M-q10k --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT                --calib-dir $D/perquery/q10k --eval-dir $D/perquery/q10k --threads ${THREADS:-4} --out results/m3_exp3.jsonl --run exp3_q10k_t${THREADS:-4}                --knob-scale ${KNOBS:-0.5,1,2} --mt ${MT:-0.0001,0.0003,0.001,0.01} --faiss-ivf S4:c=16384 --faiss-nprobe ${NPROBES:-64,256,1024} ;;
  exp1-q10k-t2) # best configurations at 2 threads, for the same-thread-count ratio against FAISS (WSL cap: 2 vCPU)
             FI_HEAP=5g $FI sweep --data $Q10K --name yfcc-10M-q10k --queries private --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT                --strategies "S0;S2:ef=16|32;S4:c=16384,nprobe=256;S4:c=4096,nprobe=256;S6:ef=32000" --threads 2 --warmup 500 --global-warmup 1000                --run q10k_t2 --out results/exp1_10m.jsonl --experiment exp1 ;;
  faiss-q10k) # The benchmark's FAISS baseline on the same 10k samples, 2 threads (WSL cap)
             for qs in private public; do
               wsl.exe -d Ubuntu-24.04 -- bash -c "bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl.sh --data-dir /mnt/c/SullaPortal/data/facetindex/yfcc_q10k --queries $qs --threads 2 --repeats 1 --out /mnt/c/Mac/Documents/FacetIndex/results/m1_faiss.jsonl"
             done ;;
  exp5-q)    # A3 at three SetAttrs rates, then A1 and A2 at 1,000/s, 150 s windows, through Kafka
             FI_HEAP=5g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4                --modes A3 --rates 100,1000,10000 --duration 150 --checkpoint 50 --via kafka --out results/exp5.jsonl
             for m in A2 A1; do  # one process per run (BUG_LOG #9)
               FI_HEAP=5g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4                  --modes $m --rates 1000 --duration 150 --checkpoint 50 --via kafka --out results/exp5.jsonl || true
             done ;;
  exp9-q)    FI_HEAP=5g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4                --modes A3 --rates 10000 --duration 150 --checkpoint 50 --via direct --experiment exp9 --out results/exp5.jsonl ;;
  exp6-q)    FI_HEAP=5g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4,$IVF16                --modes A3 --rates 1000 --qps 20,50,100 --query-strategies S2,S4,P --planner-model results/m3_cost_model.json                --planner-knob ${KNOB:?set KNOB from exp3} --duration 300 --checkpoint 100 --via kafka --experiment exp6 --out results/exp6.jsonl ;;
  exp1-10m)  # Private: all 100k queries. Public (calibration only): a 30k-query subset. Slow strategies on 20k.
             FI_HEAP=5g $FI sweep --data $Y --name yfcc-10M --queries private --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT                --strategies "$GRID10" --threads 4 --warmup 1000 --run exp1_10m --out results/exp1_10m.jsonl --experiment exp1
             FI_HEAP=5g $FI sweep --data $Y --name yfcc-10M --queries public --limit 30000 --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT                --strategies "$GRID10" --threads 4 --warmup 1000 --run exp1_10m --out results/exp1_10m_public.jsonl --experiment exp1_calibration
             FI_HEAP=5g $FI sweep --data $Y --name yfcc-10M --queries private --limit 20000 --index $D/idx/yfcc10m --ivf $IVF4,$IVF16                --strategies "$GRID10_SLOW" --threads 4 --warmup 500 --run exp1_10m --out results/exp1_10m.jsonl --experiment exp1 ;;
  exp1-1m)   for qs in public private; do
               $FI sweep --data $Y1 --name yfcc-1M-slice --queries $qs --index $D/idx/yfcc1m --ivf $D/ivf/yfcc1m_c1024.ivf                  --strategies "$GRID1" --threads 4 --run exp1_1m --out results/exp1_1m.jsonl --experiment exp1
             done ;;
  exp3-10m)  $FI exp3 --data $Y --name yfcc-10M --index $D/idx/yfcc10m --ivf $IVF4,$IVF16 --pertag $PT \
               --calib-dir $D/perquery/exp1_10m --eval-dir $D/perquery/exp1_10m --out results/m3_exp3.jsonl --run exp3_10m ;;
  exp3-1m)   $FI exp3 --data $Y1 --name yfcc-1M-slice --index $D/idx/yfcc1m --ivf $D/ivf/yfcc1m_c1024.ivf --faiss-ivf S4:c=1024 \
               --calib-dir $D/perquery/exp1_1m --eval-dir $D/perquery/exp1_1m --out results/m3_exp3_1m.jsonl \
               --model-out results/m3_cost_model_1m.json --plan-sample results/plan_1m.jsonl --run exp3_1m ;;
  faiss)     wsl -d Ubuntu-24.04 -- bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl_build.sh
             for qs in public private; do
               wsl -d Ubuntu-24.04 -- bash /mnt/c/Mac/Documents/FacetIndex/scripts/faiss_wsl.sh --queries $qs --threads 2 --repeats 1 \
                 --out /mnt/c/Mac/Documents/FacetIndex/results/m1_faiss.jsonl
             done ;;
  exp4)      FI_HEAP=6g $FI exp4 --data $D/msturing10m --runbook $D/src/big-ann-benchmarks/neurips23/runbooks/delete_runbook.yaml \
               --gt-dir $D/msturing10m/delete_runbook_gt --index $D/idx/runbook --via kafka --ef 100 --out results/exp4.jsonl ;;
  exp5)      FI_HEAP=6g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4 \
               --modes A3,A2,A1 --rates 0,100,1000,10000 --duration 120 --via kafka --out results/exp5.jsonl ;;
  exp9)      FI_HEAP=6g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4 \
               --modes A3 --rates 10000 --duration 120 --via direct --experiment exp9 --out results/exp5.jsonl ;;
  exp6)      FI_HEAP=6g $FI exp5 --data $Y --base-index $D/idx/yfcc9m_base --work-index $D/idx/work --ivf $IVF4,$IVF16 \
               --modes A3 --rates 1000 --qps 20,50,100 --query-strategies S2,S4,P --planner-model results/m3_cost_model.json \
               --duration 120 --via kafka --experiment exp6 --out results/exp6.jsonl ;;
  exp7)      $FI exp7 --data $Y1 --name yfcc-1M-slice --index $D/idx/yfcc1m --ivf $D/ivf/yfcc1m_c1024.ivf \
               --strategies "S0;S2:ef=64;S2:ef=64,filter=terms;S3:ef=64,threshold=60;S4:c=1024,nprobe=32|128" --out results/exp7.jsonl ;;
  *) echo "phases: subset exp1-q10k-private exp1-q10k-public exp3-q10k faiss-q10k exp5-q exp9-q exp6-q data m0 build exp1-10m exp1-1m exp3-10m exp3-1m faiss exp4 exp5 exp9 exp6 exp7"; exit 2 ;;
esac
