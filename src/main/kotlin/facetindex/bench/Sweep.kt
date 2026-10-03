package facetindex.bench

import facetindex.attrs.AttributeStore
import facetindex.cli.Bins
import facetindex.cli.loadStore
import facetindex.data.FilteredDataset
import facetindex.data.QuerySet
import facetindex.ivf.IvfIndex
import facetindex.strategy.Engine
import facetindex.strategy.FilterMode
import facetindex.strategy.FilterStrategy
import facetindex.strategy.IvfAsLuceneFilter
import facetindex.strategy.IvfIntersect
import facetindex.strategy.LuceneFilteredHnsw
import facetindex.strategy.PostFilterHnsw
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import facetindex.strategy.SearcherSource
import facetindex.util.Args
import facetindex.util.DataDir
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** A read-only searcher over a finished index (static benchmarks). */
class StaticSearcher(path: Path) : SearcherSource, AutoCloseable {
    private val dir = FSDirectory.open(path)
    val reader: DirectoryReader = DirectoryReader.open(dir)
    private val searcher = IndexSearcher(reader).apply { queryCache = null }
    override fun <T> withSearcher(body: (IndexSearcher) -> T): T = body(searcher)
    override fun close() { reader.close(); dir.close() }
}

/**
 * One strategy configuration, parsed from `NAME:key=v,key=v`. A value may list alternatives with `|`
 * (`S2:ef=16|32|64`), which [expand] turns into a grid.
 */
data class StrategySpec(val name: String, val params: Map<String, String>) {
    val label: String get() = name + if (params.isEmpty()) "" else params.entries.joinToString(",", ":") { "${it.key}=${it.value}" }

    fun budget() = SearchBudget(
        ef = params["ef"]?.toInt() ?: 0,
        nprobe = params["nprobe"]?.toInt() ?: 0,
        safety = params["safety"]?.toDouble() ?: 1.0,
        maxFetch = params["maxfetch"]?.toInt() ?: 10_000,
        threshold = params["threshold"]?.toInt() ?: 0,
    )

    fun clusters() = params["c"]?.toInt() ?: 4096

    companion object {
        fun parse(s: String): StrategySpec {
            val name = s.substringBefore(':')
            val params = if (':' in s) s.substringAfter(':').split(',').filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') } else emptyMap()
            return StrategySpec(name, params)
        }

        /** Splits on `;` between specs and expands `|` alternatives. */
        fun expand(spec: String): List<StrategySpec> = spec.split(';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { one ->
            val base = parse(one)
            var grid = listOf(emptyMap<String, String>())
            for ((k, v) in base.params) grid = grid.flatMap { m -> v.split('|').map { m + (k to it) } }
            grid.map { StrategySpec(base.name, it) }
        }
    }
}

fun strategyFor(e: Engine, spec: StrategySpec): FilterStrategy = when (spec.name) {
    "S0" -> PreFilterBruteForce(e)
    "S1" -> PostFilterHnsw(e)
    "S2" -> LuceneFilteredHnsw(e, "S2", 0, spec.params["filter"]?.let { FilterMode.valueOf(it.uppercase()) })
    "S3" -> LuceneFilteredHnsw(e, "S3", 60, spec.params["filter"]?.let { FilterMode.valueOf(it.uppercase()) })
    "S4" -> IvfIntersect(e, spec.clusters())
    "S4L" -> IvfAsLuceneFilter(e, spec.clusters())
    else -> e.extraStrategies[spec.name]?.invoke(spec.params) ?: throw IllegalArgumentException("unknown strategy ${spec.name}")
}

/** Per-query outcome of one configuration. */
class QueryOutcome(val q: Int, val hits: Int, val possible: Int, val latencyNs: Long, val scored: Long, val matches: Long, val strategy: String)

/**
 * Runs every query once on [threads] threads, timing each query and the whole batch. Recall uses the
 * benchmark's tie-aware count. Queries whose GT is empty (possible on a slice) are skipped and counted.
 */
fun runBatch(e: Engine, qs: QuerySet, picks: IntArray, threads: Int, k: Int, search: (Int) -> Pair<IntArray, Pair<Long, String>>): Pair<List<QueryOutcome>, Double> {
    val gt = qs.gt!!
    val out = arrayOfNulls<QueryOutcome>(picks.size)
    val next = AtomicInteger()
    val pool = Executors.newFixedThreadPool(threads)
    val t0 = System.nanoTime()
    val fs = (0 until threads).map {
        pool.submit {
            while (true) {
                val j = next.getAndIncrement()
                if (j >= picks.size) break
                val q = picks[j]
                val s = System.nanoTime()
                val (rows, extra) = search(q)
                val dt = System.nanoTime() - s
                val possible = minOf(k, gt.nValid(q))
                out[j] = QueryOutcome(q, gt.hits(q, rows, k), possible, dt, extra.first, 0, extra.second)
            }
        }
    }
    fs.forEach { it.get() }
    val wall = (System.nanoTime() - t0) / 1e9
    pool.shutdown()
    return out.map { it!! } to wall
}

object Sweep {
    /** Loads what a sweep needs: store, attribute store, IVFs, a static searcher. */
    fun engine(a: Args, ds: FilteredDataset): Pair<Engine, StaticSearcher?> {
        val attrs: AttributeStore = loadStore(ds)
        val searcher = a.pathOrNull("index")?.let { StaticSearcher(it) }
        val ivfs = a.list("ivf", "").associate { f -> IvfIndex.load(Path.of(f)).let { it.k to it } }
        val mode = FilterMode.valueOf(a.str("filter", "external").uppercase())
        val e = Engine(ds.base, attrs, searcher, ivfs, mode)
        a.pathOrNull("pertag")?.let { f ->
            val pt = facetindex.ivf.PerTagIvf.load(f)
            val s6 = facetindex.ivf.PerTagIvfStrategy(ds.base, attrs, pt, PreFilterBruteForce(e))
            e.extraStrategies["S6"] = { _ -> s6 }
        }
        return e to searcher
    }

    fun picks(a: Args, qs: QuerySet): IntArray {
        val limit = minOf(a.int("limit", qs.n), qs.n)
        val gt = qs.gt!!
        val all = (0 until qs.n).filter { gt.nValid(it) > 0 }
        val step = maxOf(1, all.size / limit)
        return all.filterIndexed { i, _ -> i % step == 0 }.take(limit).toIntArray()
    }

    /**
     * exp1/exp2: for each strategy configuration, run the query set on N threads; write one summary
     * line (overall recall@10, wall-clock QPS, and per selectivity bin and tag count: recall and mean
     * latency) and keep per-query outcomes under the data directory for the planner.
     */
    fun run(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc-10M"))
        val which = a.str("queries", "private")
        val qs = ds.queries(which)
        val (e, searcher) = engine(a, ds)
        val specs = StrategySpec.expand(a.req("strategies"))
        val threads = a.int("threads", 4)
        val k = a.int("k", 10)
        val picks = picks(a, qs)
        val stats = picks.associateWith { q -> e.stats(Predicate(qs.tagsOf(q))) }
        val perQueryDir = a.path("per-query-dir", DataDir.resolve("perquery/${a.str("run", "sweep")}"))
        Files.createDirectories(perQueryDir)
        val out = JsonlWriter(a.path("out"))
        val warm = picks.take(a.int("warmup", 2000)).toIntArray()
        for (spec in specs) {
            val strat = strategyFor(e, spec)
            val budget = spec.budget()
            val search = { q: Int ->
                val r = strat.search(qs.vector(q), Predicate(qs.tagsOf(q)), k, budget)
                r.rows to (r.scored to strat.name)
            }
            if (warm.isNotEmpty()) runBatch(e, qs, warm, threads, k, search)
            for (rep in 1..a.int("repeats", 1)) {
                val loadStart = Machine.load(500)
                val (res, wall) = runBatch(e, qs, picks, threads, k, search)
                val row = summarize(res, wall, stats, picks.size, threads) + linkedMapOf(
                    "experiment" to a.str("experiment", "exp1"), "dataset" to ds.name, "query_set" to which,
                    "strategy" to spec.name, "config" to spec.label, "params" to spec.params, "k" to k, "threads" to threads,
                    "filter_mode" to e.filterMode.name.lowercase(), "repeat" to rep, "repeats" to a.int("repeats", 1),
                    "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(500),
                )
                out.write(row)
                println("${spec.label} rep=$rep recall=${"%.4f".format(row["recall_at_10"])} qps=${"%.1f".format(row["qps"])}")
                if (rep == 1) writePerQuery(perQueryDir.resolve("${which}_${spec.label.replace(Regex("[^A-Za-z0-9=_.-]"), "_")}.tsv"), res, stats)
            }
        }
        out.close()
        searcher?.close()
        return 0
    }

    fun summarize(res: List<QueryOutcome>, wall: Double, stats: Map<Int, facetindex.strategy.PredicateStats>, n: Int, threads: Int): Map<String, Any?> {
        val hits = res.sumOf { it.hits.toLong() }
        val possible = res.sumOf { it.possible.toLong() }
        val lat = res.map { it.latencyNs / 1000.0 }.sorted()
        val byBin = res.groupBy { Bins.of(stats.getValue(it.q).selectivity) * 10 + stats.getValue(it.q).nTags }
        val bins = byBin.toSortedMap().map { (key, list) ->
            val l = list.map { it.latencyNs / 1000.0 }
            linkedMapOf(
                "bin" to Bins.labels[key / 10], "tags" to key % 10, "queries" to list.size,
                "recall_at_10" to list.sumOf { it.hits.toLong() }.toDouble() / list.sumOf { it.possible.toLong() }.coerceAtLeast(1),
                "mean_latency_us" to l.average(),
                // Throughput this bin would see if all threads ran only its queries (threads / mean latency).
                "qps_equiv" to threads * 1e6 / l.average(),
                "mean_scored" to list.map { it.scored }.average(),
            )
        }
        return linkedMapOf(
            "queries" to n, "recall_at_10" to hits.toDouble() / possible, "qps" to n / wall, "wall_s" to wall,
            "latency_us_p50" to lat[lat.size / 2], "latency_us_p99" to lat[(lat.size * 0.99).toInt().coerceAtMost(lat.size - 1)],
            "latency_us_mean" to lat.average(), "mean_scored" to res.map { it.scored }.average(), "bins" to bins,
        )
    }

    private fun writePerQuery(path: Path, res: List<QueryOutcome>, stats: Map<Int, facetindex.strategy.PredicateStats>) {
        Files.newBufferedWriter(path).use { w: BufferedWriter ->
            w.write("q\thits\tpossible\tlatency_us\tscored\tmatches\tntags\n")
            for (r in res) {
                val s = stats.getValue(r.q)
                w.write("${r.q}\t${r.hits}\t${r.possible}\t${r.latencyNs / 1000.0}\t${r.scored}\t${s.matches}\t${s.nTags}\n")
            }
        }
    }
}

