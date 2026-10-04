package facetindex.planner

import facetindex.strategy.PredicateStats
import facetindex.util.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.log10

/**
 * Calibrated cost and recall of one strategy configuration as a function of the predicate: a
 * piecewise-linear curve over log10(matching items), one per tag count (one-tag and two-tag queries
 * behave differently for the graph strategies because two-tag matches scatter more in the graph).
 * Fitted from per-query measurements on the public query set.
 */
class ConfigModel(
    val config: String,
    val strategy: String,
    /** Bin centres in log10(matches). */
    val centers: DoubleArray,
    /** [nTags - 1][bin] mean latency (microseconds) and mean recall@10. NaN where unobserved. */
    val latency: Array<DoubleArray>,
    val recall: Array<DoubleArray>,
    val counts: Array<IntArray>,
) {
    private fun interp(table: DoubleArray, x: Double): Double {
        // Linear interpolation over observed bins, flat beyond the ends.
        var lo = -1; var hi = -1
        for (i in centers.indices) if (!table[i].isNaN()) { if (centers[i] <= x) lo = i; if (centers[i] >= x && hi < 0) hi = i }
        return when {
            lo < 0 && hi < 0 -> Double.NaN
            lo < 0 -> table[hi]
            hi < 0 -> table[lo]
            lo == hi -> table[lo]
            else -> table[lo] + (table[hi] - table[lo]) * (x - centers[lo]) / (centers[hi] - centers[lo])
        }
    }

    fun cost(stats: PredicateStats): Double = interp(latency[tagIdx(stats)], log10(stats.matches.coerceAtLeast(1).toDouble()))
    fun recall(stats: PredicateStats): Double = interp(recall[tagIdx(stats)], log10(stats.matches.coerceAtLeast(1).toDouble()))
    private fun tagIdx(s: PredicateStats) = (s.nTags.coerceIn(1, 2)) - 1
}

/** One per-query measurement row (from a sweep's per-query file). */
class Observation(val q: Int, val hits: Int, val possible: Int, val latencyUs: Double, val matches: Long, val nTags: Int)

object CostModelFit {
    /** Bin edges in log10(matches): 0.25-decade bins from 1 to 10^7. */
    val edges: DoubleArray = DoubleArray(30) { it * 0.25 }

    fun fit(config: String, obs: List<Observation>): ConfigModel {
        val nb = edges.size - 1
        val lat = Array(2) { DoubleArray(nb) }
        val rec = Array(2) { DoubleArray(nb) }
        val cnt = Array(2) { IntArray(nb) }
        val hits = Array(2) { LongArray(nb) }
        val poss = Array(2) { LongArray(nb) }
        for (o in obs) {
            val t = o.nTags.coerceIn(1, 2) - 1
            val b = bin(o.matches)
            lat[t][b] += o.latencyUs; cnt[t][b]++
            hits[t][b] += o.hits.toLong(); poss[t][b] += o.possible.toLong()
        }
        for (t in 0..1) for (b in 0 until nb) {
            // Bins with very few observations are too noisy to trust.
            if (cnt[t][b] < 20) { lat[t][b] = Double.NaN; rec[t][b] = Double.NaN } else {
                lat[t][b] /= cnt[t][b]; rec[t][b] = hits[t][b].toDouble() / poss[t][b].coerceAtLeast(1)
            }
        }
        val centers = DoubleArray(nb) { (edges[it] + edges[it + 1]) / 2 }
        return ConfigModel(config, config.substringBefore(':'), centers, lat, rec, cnt)
    }

    fun bin(matches: Long): Int {
        val x = log10(matches.coerceAtLeast(1).toDouble())
        for (i in 1 until edges.size) if (x < edges[i]) return i - 1
        return edges.size - 2
    }

    fun readTsv(path: Path): List<Observation> = Files.readAllLines(path).drop(1).filter { it.isNotBlank() }.map { l ->
        val f = l.split('\t')
        Observation(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toDouble(), f[5].toLong(), f[6].toInt())
    }
}

/**
 * The planner: for each query, the exact predicate statistics pick a configuration. Two decision
 * rules over the same calibrated models:
 * - LAGRANGE: minimise predicted cost minus lambda x predicted recall (lambda in microseconds per unit
 *   of recall). One lambda trades recall for time consistently across all queries, which is what
 *   maximising QPS under a mean-recall target asks for. Lambda is chosen on the public set.
 * - THRESHOLD: the cheapest configuration whose predicted recall in the query's bin clears tau.
 */
class Planner(val models: List<ConfigModel>, val rule: Rule, val knob: Double) {
    enum class Rule { LAGRANGE, THRESHOLD }

    class Decision(val config: String, val predictedCostUs: Double, val predictedRecall: Double)

    fun choose(stats: PredicateStats): Decision {
        var best: ConfigModel? = null
        var bestScore = Double.POSITIVE_INFINITY
        var bc = 0.0; var br = 0.0
        for (m in models) {
            val c = m.cost(stats)
            val r = m.recall(stats)
            if (c.isNaN() || r.isNaN()) continue
            val score = when (rule) {
                Rule.LAGRANGE -> c - knob * r
                Rule.THRESHOLD -> if (r >= knob) c else Double.POSITIVE_INFINITY
            }
            if (score < bestScore) { bestScore = score; best = m; bc = c; br = r }
        }
        if (best == null) {
            // Nothing clears the threshold: take the highest predicted recall.
            val m = models.filter { !it.recall(stats).isNaN() }.maxBy { it.recall(stats) }
            return Decision(m.config, m.cost(stats), m.recall(stats))
        }
        return Decision(best.config, bc, br)
    }

    companion object {
        /**
         * Picks the knob on calibration observations: the setting with the least predicted total
         * cost whose predicted mean recall reaches [target]. Returns (knob, predicted recall,
         * predicted mean cost).
         */
        fun calibrate(models: List<ConfigModel>, rule: Rule, queries: List<PredicateStats>, target: Double): Triple<Double, Double, Double> {
            val grid = when (rule) {
                Rule.LAGRANGE -> (0..80).map { Math.pow(10.0, it / 10.0) } // 1 us .. 10^8 us per unit recall
                Rule.THRESHOLD -> (50..100).map { it / 100.0 }
            }
            var best: Triple<Double, Double, Double>? = null
            for (g in grid) {
                val p = Planner(models, rule, g)
                var rs = 0.0; var cs = 0.0
                for (s in queries) { val d = p.choose(s); rs += d.predictedRecall; cs += d.predictedCostUs }
                val r = rs / queries.size; val c = cs / queries.size
                if (r >= target && (best == null || c < best.third)) best = Triple(g, r, c)
            }
            return best ?: Triple(grid.last(), Double.NaN, Double.NaN)
        }

        /** Loads models written by [save] (with the chosen knob stored beside them) as a LAGRANGE planner. */
        fun load(path: Path, knob: Double): Planner {
            val root = Json.mapper.readTree(path.toFile())
            val models = root.map { n ->
                fun arr2(f: String) = Array(2) { t -> n[f][t].map { if (it.isNull) Double.NaN else it.asDouble() }.toDoubleArray() }
                ConfigModel(n["config"].asText(), n["config"].asText().substringBefore(':'), n["centers"].map { it.asDouble() }.toDoubleArray(),
                    arr2("latency_us"), arr2("recall"), Array(2) { t -> n["counts"][t].map { it.asInt() }.toIntArray() })
            }
            return Planner(models, Rule.LAGRANGE, knob)
        }

        fun save(models: List<ConfigModel>, path: Path) {
            Files.createDirectories(path.toAbsolutePath().parent)
            Files.writeString(path, Json.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(models.map {
                mapOf("config" to it.config, "centers" to it.centers, "latency_us" to it.latency, "recall" to it.recall, "counts" to it.counts)
            }).replace("\"NaN\"", "null").replace("NaN", "null"))
        }
    }
}
