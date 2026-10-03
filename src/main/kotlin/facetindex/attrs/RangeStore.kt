package facetindex.attrs

import facetindex.data.QueryVector
import facetindex.data.VectorStore
import facetindex.strategy.RangeClause
import org.roaringbitmap.RoaringBitmap
import java.util.SplittableRandom

/**
 * Integer attributes for range predicates: per attribute, each row's value plus the rows sorted by
 * value, so a range's exact count is two binary searches and its rows are one contiguous slice.
 * Static (built once); range attributes are not updated by the streaming layer.
 */
class RangeStore(val n: Int, private val values: Map<String, LongArray>) {
    private val sortedRows = HashMap<String, IntArray>()
    private val sortedVals = HashMap<String, LongArray>()

    init {
        for ((name, v) in values) {
            val order = (0 until n).sortedBy { v[it] }.toIntArray()
            sortedRows[name] = order
            sortedVals[name] = LongArray(n) { v[order[it]] }
        }
    }

    val attributes: Set<String> get() = values.keys
    fun value(attr: String, row: Int): Long = values.getValue(attr)[row]
    fun valuesOf(attr: String): LongArray = values.getValue(attr)

    fun pass(row: Int, clauses: List<RangeClause>): Boolean {
        for (c in clauses) { val v = values.getValue(c.attr)[row]; if (v < c.lo || v > c.hi) return false }
        return true
    }

    private fun bounds(c: RangeClause): IntRange {
        val sv = sortedVals.getValue(c.attr)
        val lo = lowerBound(sv, c.lo)
        val hi = lowerBound(sv, c.hi + 1)
        return lo until hi
    }

    /** Exact number of rows inside one clause. */
    fun count(c: RangeClause): Int = bounds(c).let { it.last - it.first + 1 }

    /** Rows inside one clause, in value order. */
    fun rows(c: RangeClause): IntArray = bounds(c).let { sortedRows.getValue(c.attr).copyOfRange(it.first, it.last + 1) }

    /** Exact set of rows passing every clause (iterates the narrowest clause's slice). */
    fun matching(clauses: List<RangeClause>): RoaringBitmap {
        val lead = clauses.minBy { count(it) }
        val rest = clauses.filter { it !== lead }
        val out = RoaringBitmap()
        val r = bounds(lead)
        val order = sortedRows.getValue(lead.attr)
        val buf = IntArray(r.last - r.first + 1)
        var m = 0
        for (i in r) { val row = order[i]; if (rest.isEmpty() || pass(row, rest)) buf[m++] = row }
        buf.sort(0, m)
        out.addN(buf, 0, m)
        return out
    }

    /** The value at a quantile, for building ranges of a chosen selectivity. */
    fun quantile(attr: String, q: Double): Long = sortedVals.getValue(attr)[(q * (n - 1)).toInt().coerceIn(0, n - 1)]

    fun lowerBoundPublic(attr: String, key: Long): Int = lowerBound(sortedVals.getValue(attr), key)

    /** Value at a rank in sorted order. */
    fun valueAtRank(attr: String, rank: Int): Long = sortedVals.getValue(attr)[rank.coerceIn(0, n - 1)]

    private fun lowerBound(a: LongArray, key: Long): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] < key) lo = mid + 1 else hi = mid }
        return lo
    }

    companion object {
        /**
         * Two constructed attributes for the range experiment (labelled constructed wherever used):
         * `uniform`, drawn independently of everything (seeded), and `proj`, the vector's projection on
         * a fixed random direction scaled to an integer, so it is correlated with the vector (nearby
         * vectors have nearby values).
         */
        fun constructed(store: VectorStore, n: Int, seed: Long = 77L): RangeStore {
            val rnd = SplittableRandom(seed)
            val uniform = LongArray(n) { rnd.nextLong(1_000_000L) }
            val project = projector(store.dim, seed)
            val proj = LongArray(n) { r -> project(store.query(r)) }
            return RangeStore(n, linkedMapOf("uniform" to uniform, "proj" to proj))
        }

        /** The `proj` attribute's function, usable on query vectors too (same seed, same direction). */
        fun projector(dim: Int, seed: Long = 77L): (QueryVector) -> Long {
            // The direction has its own derived seed, independent of the uniform attribute's stream.
            val dr = SplittableRandom(seed * 31 + 7)
            val dir = DoubleArray(dim) { dr.nextGaussian() }
            return { q ->
                var s = 0.0
                when (q) {
                    is QueryVector.U8 -> for (i in dir.indices) s += dir[i] * (q.v[i].toInt() and 0xFF)
                    is QueryVector.F32 -> for (i in dir.indices) s += dir[i] * q.v[i]
                }
                Math.round(s * 100)
            }
        }

        /** Rank of [v] among an attribute's sorted values (for windows around a value). */
        fun rankOf(rs: RangeStore, attr: String, v: Long): Int = rs.lowerBoundPublic(attr, v)
    }
}
