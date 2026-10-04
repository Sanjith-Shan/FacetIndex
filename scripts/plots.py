#!/usr/bin/env python3
"""Builds the write-up figures (docs/img/*.png) from results/*.jsonl. Every figure states its machine.

    python scripts/plots.py
"""
import json
import os
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "results")
IMG = os.path.join(ROOT, "docs", "img")

# Fixed entity colours (categorical slots in fixed order; colour follows the strategy, never its rank).
COLOR = {
    "S0": "#2a78d6", "S2": "#eb6834", "S3": "#1baf7a", "S4": "#eda100",
    "S4L": "#e87ba4", "S6": "#008300", "S5": "#4a3aa7", "S1": "#e34948",
    "planner": "#0b0b0b", "faiss": "#52514e",
}
MARK = {"S0": "o", "S1": "v", "S2": "s", "S3": "D", "S4": "^", "S4L": "<", "S5": "P", "S6": "X", "planner": "*", "faiss": "h"}
TEXT2 = "#52514e"
MACHINE = "mini PC: AMD Ryzen 3 4300U, 4 cores, 14.9 GB"


def rows(name):
    p = os.path.join(RES, name)
    if not os.path.exists(p):
        return []
    with open(p) as f:
        return [json.loads(line) for line in f if line.strip()]


def style(ax, title, xlabel, ylabel):
    ax.set_title(title, loc="left", fontsize=11)
    ax.set_xlabel(xlabel, color=TEXT2)
    ax.set_ylabel(ylabel, color=TEXT2)
    ax.grid(True, color="#e6e5e0", linewidth=0.8)
    ax.set_axisbelow(True)
    for s in ("top", "right"):
        ax.spines[s].set_visible(False)
    for s in ("left", "bottom"):
        ax.spines[s].set_color("#b8b7b0")
    ax.tick_params(colors=TEXT2)


def pareto(points):
    """Upper-left frontier of (recall, qps) points: for increasing recall, the best qps."""
    pts = sorted(points, key=lambda p: (-p[1], -p[0]))
    out, best_r = [], -1
    for r, q in pts:
        if r > best_r:
            out.append((r, q))
            best_r = r
    return sorted(out)


def curve_figure(fname, title, series, note):
    fig, ax = plt.subplots(figsize=(7.5, 4.6), dpi=130)
    for name, pts in series.items():
        if not pts:
            continue
        fr = pareto(pts)
        ax.plot([p[0] for p in fr], [p[1] for p in fr], color=COLOR.get(name, "#888"), linewidth=2,
                marker=MARK.get(name, "o"), markersize=7, label=name)
    ax.axvline(0.9, color="#b8b7b0", linewidth=1, linestyle="--")
    ax.set_yscale("log")
    style(ax, title, "recall@10", "queries per second (log)")
    ax.legend(frameon=False, fontsize=9, ncol=2)
    fig.text(0.01, 0.01, note, fontsize=7.5, color=TEXT2)
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    os.makedirs(IMG, exist_ok=True)
    fig.savefig(os.path.join(IMG, fname))
    plt.close(fig)


def exp1():
    data = [r for r in rows("exp1_10m.jsonl") if r.get("repeat", 1) == 1]
    series = defaultdict(list)
    for r in data:
        if r["threads"] == 4:
            series[r["strategy"]].append((r["recall_at_10"], r["qps"]))
    for r in rows("m3_exp3.jsonl"):
        if r.get("planner") == "fitted" and r.get("threads") == 4:
            series["planner"].append((r["recall_at_10"], r["qps"]))
    if series:
        curve_figure("exp1_strategies.png", "yfcc-10M, 10k stratified private queries, 4 threads", series,
                     f"{MACHINE}; best configurations per strategy; results/exp1_10m.jsonl, m3_exp3.jsonl")
    # Same-core-count comparison with the FAISS baseline (WSL is capped at 2 vCPUs on this box).
    s2 = defaultdict(list)
    for r in rows("m1_faiss.jsonl"):
        s2["faiss"].append((r["recall_at_10"], r["qps"]))
    for r in rows("m3_exp3.jsonl"):
        if r.get("planner") == "fitted" and r.get("threads") == 2 and "pinned" in r.get("machine", {}).get("label", ""):
            s2["planner"].append((r["recall_at_10"], r["qps"]))
    for r in data:
        if r["threads"] == 2 and r["strategy"] in ("S2", "S6", "S4"):
            s2[r["strategy"]].append((r["recall_at_10"], r["qps"]))
    if s2:
        curve_figure("exp1_vs_faiss.png", "Against the FAISS filter baseline: 2 threads each", s2,
                     f"{MACHINE}; FAISS: WSL, 2 vCPU; planner also pinned to 2 cores; results/m1_faiss.jsonl, m3_exp3.jsonl")


def exp2():
    data = [r for r in rows("exp1_10m.jsonl") if r["threads"] == 4]
    if not data:
        return
    bins = ["<0.01%", "0.01-0.1%", "0.1-1%", "1-10%", ">10%"]
    best = defaultdict(dict)  # strategy -> bin -> best qps_equiv with recall >= 0.9
    for r in data:
        for b in r.get("bins", []):
            key = b["bin"]
            if b["recall_at_10"] >= 0.9:
                best[r["strategy"]][key] = max(best[r["strategy"]].get(key, 0), b["qps_equiv"])
    strategies = [s for s in ["S0", "S2", "S3", "S4", "S4L", "S6", "S1"] if s in best]
    fig, ax = plt.subplots(figsize=(8, 4.4), dpi=130)
    w = 0.8 / max(1, len(strategies))
    for i, s in enumerate(strategies):
        xs = [j + (i - len(strategies) / 2) * w + w / 2 for j in range(len(bins))]
        ys = [best[s].get(b, 0) for b in bins]
        ax.bar(xs, ys, width=w * 0.9, color=COLOR[s], label=s)
    ax.set_xticks(range(len(bins)))
    ax.set_xticklabels(bins)
    ax.set_yscale("log")
    style(ax, "Best throughput at recall@10 >= 0.9 by selectivity bin (yfcc-10M, 4 threads)", "selectivity bin", "QPS equivalent (log)")
    ax.legend(frameon=False, fontsize=9, ncol=4)
    fig.text(0.01, 0.01, f"{MACHINE}; missing bar: the strategy never reaches 0.9 in that bin", fontsize=7.5, color=TEXT2)
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    fig.savefig(os.path.join(IMG, "exp2_bins.png"))
    plt.close(fig)


def exp5():
    data = [r for r in rows("exp5.jsonl") if r["via"] == "kafka"]
    if not data:
        return
    order = sorted(data, key=lambda r: (r["mode"] != "A3", r["mode"], r["set_attrs_rate"]))
    labels = [f"{r['mode']} {int(r['set_attrs_rate']):,}/s" for r in order]
    # For A3 a change is visible once applied; for A1/A2 once a refresh exposes the Lucene write.
    p50 = [(r["attr_apply_lag_us"]["p50"] if r["mode"] == "A3" else max(r["attr_apply_lag_us"]["p50"], r["lucene_visibility_lag_us"]["p50"])) / 1000 for r in order]
    p99 = [(r["attr_apply_lag_us"]["p99"] if r["mode"] == "A3" else max(r["attr_apply_lag_us"]["p99"], r["lucene_visibility_lag_us"]["p99"])) / 1000 for r in order]
    fig, ax = plt.subplots(figsize=(7.5, 4.2), dpi=130)
    xs = range(len(labels))
    ax.bar([x - 0.2 for x in xs], p50, width=0.38, color="#2a78d6", label="p50")
    ax.bar([x + 0.2 for x in xs], p99, width=0.38, color="#eb6834", label="p99")
    ax.set_xticks(list(xs))
    ax.set_xticklabels(labels)
    ax.set_yscale("log")
    style(ax, "Attribute change to visible in search, through Kafka (constructed workload)", "attribute path and SetAttrs rate", "milliseconds (log)")
    ax.legend(frameon=False, fontsize=9)
    fig.text(0.01, 0.01, f"{MACHINE}; every run applied all offered updates; results/exp5.jsonl", fontsize=7.5, color=TEXT2)
    fig.tight_layout(rect=(0, 0.04, 1, 1))
    fig.savefig(os.path.join(IMG, "exp5_updates.png"))
    plt.close(fig)


def exp4():
    data = [r for r in rows("exp4.jsonl") if r["experiment"] == "exp4_checkpoint"]
    if not data:
        return
    fig, ax = plt.subplots(figsize=(7, 3.8), dpi=130)
    ax.plot([r["step"] for r in data], [r["recall_at_10"] for r in data], color="#2a78d6", marker="o", markersize=5, linewidth=2)
    style(ax, "Streaming runbook: recall@10 at each search checkpoint", "runbook step", "recall@10")
    fig.text(0.01, 0.01, f"{MACHINE}; msturing-10M-clustered, delete_runbook.yaml, through Kafka", fontsize=7.5, color=TEXT2)
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    fig.savefig(os.path.join(IMG, "exp4_checkpoints.png"))
    plt.close(fig)


if __name__ == "__main__":
    exp1()
    exp2()
    exp5()
    print("figures in", IMG)
