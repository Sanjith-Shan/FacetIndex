#!/usr/bin/env python3
"""Generates the measured tables of NUMBERS.md from results/*.jsonl, naming the file of every number.

    python scripts/numbers.py > docs/numbers_generated.md

Population weighting: the 10k samples hold 2,000 queries per selectivity bin; the full private set's
bin shares come from results/m0_data.jsonl. Weighted recall is sum(w_b * recall_b); weighted QPS
scales the measured QPS by (mean latency of the sample) / (bin-weighted mean latency).
"""
import json
import os
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "results")
BINS = ["<0.01%", "0.01-0.1%", "0.1-1%", "1-10%", ">10%"]


def rows(name):
    p = os.path.join(RES, name)
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return [json.loads(l) for l in f if l.strip()]


def weights():
    for r in rows("m0_data.jsonl"):
        if r.get("kind") == "queries" and r.get("query_set") == "private":
            h = r["selectivity_hist"]
            t = sum(h)
            return {b: h[i] / t for i, b in enumerate(BINS)}
    return {b: 0.2 for b in BINS}


W = weights()


def per_bin(r):
    """Aggregate a summary's (bin, tag count) entries to bins: recall, mean latency, queries."""
    agg = defaultdict(lambda: [0, 0.0, 0.0])
    for b in r.get("bins", []):
        a = agg[b["bin"]]
        a[0] += b["queries"]
        a[1] += b["recall_at_10"] * b["queries"]
        a[2] += b["mean_latency_us"] * b["queries"]
    return {k: (v[1] / v[0], v[2] / v[0], v[0]) for k, v in agg.items() if v[0]}


def weighted(r):
    pb = per_bin(r)
    if len(pb) < len(BINS):
        return None, None
    rec = sum(W[b] * pb[b][0] for b in BINS)
    lat_w = sum(W[b] * pb[b][1] for b in BINS)
    lat_s = sum(pb[b][1] * pb[b][2] for b in BINS) / sum(pb[b][2] for b in BINS)
    return rec, r["qps"] * lat_s / lat_w


def f(x, nd=1):
    return "n/a" if x is None else (f"{x:,.{nd}f}" if isinstance(x, float) else f"{x:,}")


def exp1(name, label):
    data = [r for r in rows(name) if r.get("repeat", 1) == 1]
    if not data:
        return
    print(f"\n### {label} (`results/{name}`)\n")
    print("| config | threads | recall@10 (sample) | QPS (sample) | recall@10 (population-weighted) | QPS (population-weighted) | p50 / p99 latency (us) |")
    print("|---|---|---|---|---|---|---|")
    for r in data:
        wr, wq = weighted(r)
        print(f"| `{r['config']}` | {r['threads']} | {r['recall_at_10']:.4f} | {r['qps']:,.1f} | {f(wr, 4) if wr else 'n/a'} | {f(wq)} | {r['latency_us_p50']:,.0f} / {r['latency_us_p99']:,.0f} |")
    print("\nBest QPS at recall@10 >= 0.90, per strategy:\n")
    print("| strategy | config | threads | recall@10 | QPS (sample) |")
    print("|---|---|---|---|---|")
    best = {}
    for r in data:
        if r["recall_at_10"] >= 0.9:
            key = (r["strategy"], r["threads"])
            if key not in best or r["qps"] > best[key]["qps"]:
                best[key] = r
    for (s, t), r in sorted(best.items()):
        print(f"| {s} | `{r['config']}` | {t} | {r['recall_at_10']:.4f} | {r['qps']:,.1f} |")
    print("\nBy selectivity bin (exp2): best throughput at bin recall@10 >= 0.90 (QPS equivalent = threads / mean latency):\n")
    print("| strategy | " + " | ".join(BINS) + " |")
    print("|---|" + "---|" * len(BINS))
    table = defaultdict(dict)
    for r in data:
        if r["threads"] != 4:
            continue
        for b, (rec, lat, n) in per_bin(r).items():
            if rec >= 0.9:
                q = 4e6 / lat
                if q > table[r["strategy"]].get(b, (0, ""))[0]:
                    table[r["strategy"]][b] = (q, r["config"])
    for s in sorted(table):
        print(f"| {s} | " + " | ".join(f"{table[s][b][0]:,.0f} (`{table[s][b][1].split(':', 1)[-1]}`)" if b in table[s] else "never 0.9" for b in BINS) + " |")


def faiss():
    data = rows("m1_faiss.jsonl")
    if not data:
        return
    print("\n### FAISS filter baseline rerun on the mini PC (`results/m1_faiss.jsonl`)\n")
    print("| query set | nprobe | mt_threshold | threads | recall@10 | QPS |")
    print("|---|---|---|---|---|---|")
    for r in data:
        print(f"| {r.get('query_set')} | {r.get('nprobe')} | {r.get('mt_threshold')} | {r.get('threads')} | {r.get('recall_at_10', 0):.4f} | {r.get('qps', 0):,.1f} |")


def exp3():
    data = rows("m3_exp3.jsonl")
    if not data:
        return
    print("\n### Planner (exp3, `results/m3_exp3.jsonl`)\n")
    print("| planner | setting | recall@10 | QPS | QPS from latencies | notes |")
    print("|---|---|---|---|---|---|")
    for r in data:
        p = r.get("planner")
        if p == "fitted":
            print(f"| fitted ({r['rule']}) | knob {r['knob']:.3g}{' (calibrated)' if r.get('calibrated') else ''} | {r['recall_at_10']:.4f} | {r['qps']:,.1f} | {r.get('qps_equiv_from_latency', 0):,.1f} | mix {r.get('choice_mix')}, overhead mean {r.get('planner_overhead_us_mean', 0):.1f} us |")
        elif p == "faiss_rule":
            print(f"| FAISS rule in FacetIndex | mt {r['mt_threshold']}, `{r['ivf']}` | {r['recall_at_10']:.4f} | {r['qps']:,.1f} | | |")
        elif p == "oracle":
            print(f"| oracle | pass {r['pass_fraction']} | {r['recall_at_10']:.4f} | | {r['qps_equiv_from_latency']:,.1f} | {r.get('strategy_mix')} |")
        elif p == "misroutes":
            print(f"| misroutes | {r['queries']} queries | | | | differs from oracle {r['differs_from_oracle']}, over 2x oracle latency {r['more_than_2x_oracle_latency']}, below 0.9 on the query {r['below_0_9_recall_on_query']} |")


def exp5(name, label):
    data = rows(name)
    if not data:
        return
    print(f"\n### {label} (`results/{name}`)\n")
    print("| exp | mode | via | SetAttrs/s offered | applied/s | backlog at end | drain s | attr apply lag p50/p99 (us) | Lucene visibility p50/p99 (us) | last-checkpoint recall | query p99 (us) | merge s/min |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in data:
        a = r.get("attr_apply_lag_us", {})
        v = r.get("lucene_visibility_lag_us", {})
        cp = (r.get("checkpoints") or [{}])[-1].get("recall_at_10", {})
        ql = r.get("query_latency_us", {})
        print(f"| {r['experiment']} | {r['mode']} | {r['via']} | {r['set_attrs_rate']:,.0f} | {r['achieved_set_attrs_rate']:,.0f} | {r['backlog_after_drain']:,} | {r['drain_s']:.1f} | "
              f"{a.get('p50', 'n/a')}/{a.get('p99', 'n/a')} | {v.get('p50', 'n/a')}/{v.get('p99', 'n/a')} | "
              + ", ".join(f"{k} {x:.3f}" for k, x in cp.items()) + " | "
              + ", ".join(f"{k} {x.get('p99', 'n/a')}" for k, x in ql.items()) + f" | {r.get('merge_s_per_min', 0):.1f} |")


if __name__ == "__main__":
    print("<!-- generated by scripts/numbers.py from results/*.jsonl; do not edit by hand -->")
    print("\nPopulation weights (private query set bin shares, `results/m0_data.jsonl`): " + ", ".join(f"{b} {W[b]:.3f}" for b in BINS))
    exp1("exp1_10m.jsonl", "exp1 / exp2: strategies on yfcc-10M, 10k stratified private queries")
    exp1("exp1_10m_public.jsonl", "Calibration sweep: 10k stratified public queries")
    faiss()
    exp3()
    exp5("exp5.jsonl", "exp5 / exp9: constructed filtered streaming workload on YFCC")
    exp5("exp6.jsonl", "exp6: mixed workload")
