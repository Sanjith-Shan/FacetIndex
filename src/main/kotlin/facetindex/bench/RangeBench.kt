package facetindex.bench

import facetindex.attrs.RangeStore
import facetindex.data.FilteredDataset
import facetindex.strategy.Engine
import facetindex.strategy.Predicate
import facetindex.strategy.RangeClause
import facetindex.strategy.SearchBudget
import facetindex.util.Args
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import java.util.SplittableRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln

/**
 * exp7 (M5): range and tag-plus-range predicates on constructed integer attributes over YFCC (the
 * arxiv-for-fanns data carries no license, so it was not used). Ranges are built per query at a
 * target selectivity on `uniform` (independent of the vector) or on `proj` (a projection of the
 * vector), either around the query's own projection (`near`, positively correlated with the query)
 * or half the value order away (`far`, negatively correlated). Ground truth is exact (S0).
 */
object RangeBench {
    fun run(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc-1M-slice"))
        val (base, searcher) = Sweep.engine(a, ds)
        val ranges = RangeStore.constructed(ds.base, ds.base.size)
        val e = Engine(base.store, base.attrs, base.searchers, base.ivfs, base.filterMode, ranges)
        val project = RangeStore.projector(ds.base.dim)
        val qs = ds.queries(a.str("queries", "private"))
        val nq = minOf(a.int("limit", 2000), qs.n)
        val threads = a.int("threads", 4)
        val specs = StrategySpec.expand(a.req("strategies"))
        val out = JsonlWriter(a.path("out"))
        val kinds = a.list("kinds", "range,tag+range")
        val attrsKinds = a.list("attrs", "uniform,proj-near,proj-far")
        for (kind in kinds) for (ak in attrsKinds) for (sel in a.doubles("selectivity", "0.001,0.01,0.1,0.5")) {
            val rnd = SplittableRandom(11)
            // One predicate per query.
            val preds = (0 until nq).map { i ->
                val attr = if (ak == "uniform") "uniform" else "proj"
                val width = (sel * ranges.n).toInt().coerceAtLeast(1)
                val start = when (ak) {
                    "uniform" -> rnd.nextInt(maxOf(1, ranges.n - width))
                    else -> {
                        val r = RangeStore.rankOf(ranges, "proj", project(qs.vector(i)))
                        val centre = if (ak == "proj-near") r else (r + ranges.n / 2) % ranges.n
                        (centre - width / 2).coerceIn(0, ranges.n - width)
                    }
                }
                val clause = RangeClause(attr, ranges.valueAtRank(attr, start), ranges.valueAtRank(attr, start + width - 1))
                Predicate(if (kind == "range") IntArray(0) else qs.tagsOf(i), listOf(clause))
            }
            // Exact answers and the planner-side estimate error.
            val s0 = strategyFor(e, StrategySpec.parse("S0"))
            val truth = arrayOfNulls<Set<Int>>(nq)
            val logErr = DoubleArray(nq)
            val exactCounts = LongArray(nq)
            parallelFor(nq, threads) { i ->
                truth[i] = s0.search(qs.vector(i), preds[i], 10, SearchBudget()).rows.toSet()
                val est = e.stats(preds[i]).matches.coerceAtLeast(1)
                val ex = e.exactMatches(preds[i]).coerceAtLeast(1)
                exactCounts[i] = ex
                logErr[i] = abs(ln(est.toDouble() / ex))
            }
            val valid = (0 until nq).filter { truth[it]!!.isNotEmpty() }
            val errSorted = valid.map { logErr[it] }.sorted()
            for (spec in specs) {
                val strat = strategyFor(e, spec)
                val budget = spec.budget()
                // Warm up, then time.
                parallelFor(minOf(200, valid.size), threads) { j -> strat.search(qs.vector(valid[j]), preds[valid[j]], 10, budget) }
                val hits = java.util.concurrent.atomic.AtomicLong()
                val lat = LongArray(valid.size)
                val t0 = System.nanoTime()
                parallelFor(valid.size, threads) { j ->
                    val i = valid[j]
                    val st = System.nanoTime()
                    val r = strat.search(qs.vector(i), preds[i], 10, budget)
                    lat[j] = System.nanoTime() - st
                    hits.addAndGet(r.rows.count { it in truth[i]!! }.toLong())
                }
                val wall = (System.nanoTime() - t0) / 1e9
                val possible = valid.sumOf { truth[it]!!.size.toLong() }
                val row = linkedMapOf<String, Any?>(
                    "experiment" to "exp7", "dataset" to ds.name, "attributes" to "constructed (uniform: independent; proj: projection of the vector)",
                    "kind" to kind, "attr" to ak, "target_selectivity" to sel, "queries" to valid.size, "queries_without_matches" to nq - valid.size,
                    "matches_p50" to valid.map { exactCounts[it] }.sorted()[valid.size / 2],
                    "estimate_abs_log_error_p50" to errSorted[errSorted.size / 2], "estimate_abs_log_error_p90" to errSorted[(errSorted.size * 0.9).toInt()],
                    "strategy" to spec.name, "config" to spec.label, "recall_at_10" to hits.get().toDouble() / possible,
                    "qps" to valid.size / wall, "latency_us_p50" to lat.sorted()[lat.size / 2] / 1000.0, "threads" to threads,
                    "machine" to Machine.info, "load" to Machine.load(300), "repeats" to 1,
                )
                out.write(row)
                println("$kind $ak sel=$sel ${spec.label} recall=${"%.4f".format(row["recall_at_10"])} qps=${"%.1f".format(row["qps"])} logerr_p50=${"%.3f".format(row["estimate_abs_log_error_p50"])}")
            }
        }
        out.close()
        searcher?.close()
        return 0
    }

    private fun parallelFor(n: Int, threads: Int, body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val next = AtomicInteger()
        (0 until threads).map { pool.submit { while (true) { val i = next.getAndIncrement(); if (i >= n) break; body(i) } } }.forEach { it.get() }
        pool.shutdown()
    }
}
