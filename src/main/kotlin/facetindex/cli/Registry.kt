package facetindex.cli

import facetindex.bench.Sweep
import facetindex.util.Args

object Registry {
    val commands: Map<String, Pair<String, (Args) -> Int>> = linkedMapOf(
        "build-ivf" to ("k-means on a sample, assign all rows, save the IVF" to BuildCommands::buildIvf),
        "build-index" to ("build or append to the Lucene index, force-merge, optional snapshot copy" to BuildCommands::buildIndex),
        "smoke" to ("CI smoke: every strategy against brute force on a generated dataset" to facetindex.bench.Smoke::run),
        "sweep" to ("exp1/exp2: recall@10 and QPS per strategy configuration" to Sweep::run),
        "exp4" to ("official streaming runbook replay, scored the track's way" to facetindex.bench.RunbookBench::run),
        "exp5" to ("constructed filtered streaming workload: A1/A2/A3 x SetAttrs rates" to facetindex.bench.StreamBench::run),
        "exp3" to ("planner vs best fixed, FAISS rule and oracle" to facetindex.bench.PlannerBench::run),
    )
}
