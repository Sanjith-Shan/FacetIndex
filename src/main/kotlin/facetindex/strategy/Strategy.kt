package facetindex.strategy

import facetindex.data.QueryVector

/** One numeric range clause on an integer attribute: lo <= value <= hi. */
data class RangeClause(val attr: String, val lo: Long, val hi: Long)

/** A conjunctive predicate: every tag must be present and every range must hold. */
class Predicate(val tags: IntArray, val ranges: List<RangeClause> = emptyList()) {
    override fun toString() = "tags=${tags.toList()}" + if (ranges.isEmpty()) "" else " ranges=$ranges"

    companion object {
        val ALL = Predicate(IntArray(0))
    }
}

/** What the planner knows about a predicate before running anything. */
data class PredicateStats(
    /** Matching live items (exact for tags; an estimate when ranges are involved). */
    val matches: Long,
    val liveItems: Long,
    val nTags: Int,
    val exact: Boolean,
) {
    val selectivity: Double get() = if (liveItems == 0L) 0.0 else matches.toDouble() / liveItems
}

/** Per-query knobs. Each strategy reads the ones it uses. */
data class SearchBudget(
    /** Graph beam (HNSW strategies run with k' = max(k, ef) and keep the top k). */
    val ef: Int = 0,
    /** IVF clusters probed. */
    val nprobe: Int = 0,
    /** Post-filter over-fetch safety factor: k' = k / selectivity * safety. */
    val safety: Double = 1.0,
    /** Upper bound on post-filter k'. */
    val maxFetch: Int = 10_000,
    /** Lucene's filteredSearchThreshold (percent) for the ACORN-1 strategy. */
    val threshold: Int = 0,
) {
    fun describe(): Map<String, Any> = buildMap {
        if (ef > 0) put("ef", ef)
        if (nprobe > 0) put("nprobe", nprobe)
        if (safety != 1.0) put("safety", safety)
        if (threshold > 0) put("threshold", threshold)
    }
}

/** Result rows (dataset ids) in ascending distance order, with work counters. */
class SearchResult(
    val rows: IntArray,
    val dists: FloatArray,
    /** Vectors whose distance was computed (exact strategies) or nodes visited (graph, when known). */
    val scored: Long = 0,
    val note: String? = null,
) {
    companion object {
        val EMPTY = SearchResult(IntArray(0), FloatArray(0))
    }
}

interface FilterStrategy {
    val name: String
    fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult

    /** Predicted cost in microseconds, fitted at calibration (see the planner). */
    fun estimateCostMicros(stats: PredicateStats, k: Int, budget: SearchBudget): Double = Double.NaN
}

/**
 * Bounded max-heap of (distance, row) keeping the k smallest. Ties break on the smaller row so
 * results are deterministic.
 */
class TopK(private val k: Int) {
    private val d = FloatArray(k)
    private val r = IntArray(k)
    var size = 0
        private set

    /** Current admission bound: candidates at or above it cannot enter a full heap. */
    fun bound(): Float = if (size < k) Float.POSITIVE_INFINITY else d[0]

    fun offer(dist: Float, row: Int) {
        if (k == 0) return
        if (size < k) {
            d[size] = dist; r[size] = row; size++
            siftUp(size - 1)
        } else if (dist < d[0] || (dist == d[0] && row < r[0])) {
            d[0] = dist; r[0] = row
            siftDown(0)
        }
    }

    private fun worse(i: Int, j: Int) = d[i] > d[j] || (d[i] == d[j] && r[i] > r[j])

    private fun swap(i: Int, j: Int) {
        val td = d[i]; d[i] = d[j]; d[j] = td
        val tr = r[i]; r[i] = r[j]; r[j] = tr
    }

    private fun siftUp(i0: Int) {
        var i = i0
        while (i > 0) {
            val p = (i - 1) / 2
            if (worse(i, p)) { swap(i, p); i = p } else break
        }
    }

    private fun siftDown(i0: Int) {
        var i = i0
        while (true) {
            val l = 2 * i + 1
            if (l >= size) break
            var c = l
            if (l + 1 < size && worse(l + 1, l)) c = l + 1
            if (worse(c, i)) { swap(c, i); i = c } else break
        }
    }

    fun result(scored: Long = 0, note: String? = null): SearchResult {
        val idx = (0 until size).sortedWith(compareBy<Int>({ d[it] }, { r[it] }))
        return SearchResult(IntArray(size) { r[idx[it]] }, FloatArray(size) { d[idx[it]] }, scored, note)
    }
}
