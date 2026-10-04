package facetindex.ivf

import facetindex.attrs.AttributeStore
import facetindex.data.QueryVector
import facetindex.data.VectorStore
import facetindex.simd.L2
import facetindex.strategy.FilterStrategy
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import facetindex.strategy.SearchResult
import facetindex.strategy.TopK
import facetindex.data.U8Store
import org.roaringbitmap.RoaringBitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * S6's structure, after ParlayANN's IVF^2 (the filtered track's winning entry): every tag carried by
 * at least [minCount] items gets its own small IVF, trained only on that tag's items, so probing a
 * frequent tag's nearest cells returns candidates that all carry the tag instead of the 1% of a
 * global cell that does. Rare tags get nothing (S0 handles them). Cell members are row ids; vectors
 * are read from the base store.
 */
class PerTagIvf(val minCount: Int, val perCell: Int, private val tags: Map<Int, TagIndex>) {
    class TagIndex(val tag: Int, val k: Int, val d: Int, val centroids: FloatArray, val cells: Array<RoaringBitmap>) {
        fun probe(qf: FloatArray, nprobe: Int): IntArray {
            val n = minOf(nprobe, k)
            val top = TopK(n)
            for (c in 0 until k) { val dd = L2.f32(qf, centroids, c * d, d); if (dd < top.bound()) top.offer(dd, c) }
            return top.result().rows
        }
    }

    val indexedTags: Set<Int> get() = tags.keys
    fun of(tag: Int): TagIndex? = tags[tag]
    fun bytes(): Long = tags.values.sumOf { t -> t.centroids.size * 4L + t.cells.sumOf { it.serializedSizeInBytes().toLong() } }

    fun save(path: Path) {
        Files.createDirectories(path.toAbsolutePath().parent)
        DataOutputStream(Files.newOutputStream(path).buffered(1 shl 20)).use { o ->
            o.writeInt(minCount); o.writeInt(perCell); o.writeInt(tags.size)
            for (t in tags.values) {
                o.writeInt(t.tag); o.writeInt(t.k); o.writeInt(t.d)
                for (v in t.centroids) o.writeFloat(v)
                for (c in t.cells) { val a = c.toArray(); o.writeInt(a.size); for (r in a) o.writeInt(r) }
            }
        }
    }

    companion object {
        fun load(path: Path): PerTagIvf = DataInputStream(Files.newInputStream(path).buffered(1 shl 20)).use { i ->
            val minCount = i.readInt(); val perCell = i.readInt(); val n = i.readInt()
            val m = HashMap<Int, TagIndex>(n * 2)
            repeat(n) {
                val tag = i.readInt(); val k = i.readInt(); val d = i.readInt()
                val c = FloatArray(k * d) { i.readFloat() }
                val cells = Array(k) { val sz = i.readInt(); RoaringBitmap.bitmapOf(*IntArray(sz) { i.readInt() }) }
                m[tag] = TagIndex(tag, k, d, c, cells)
            }
            PerTagIvf(minCount, perCell, m)
        }

        /**
         * Builds one IVF per frequent tag: k = clamp(count / perCell, 1, maxCells) cells, k-means on a
         * sample of up to [samplePerCell] x k of the tag's items, then every item assigned.
         */
        fun build(store: VectorStore, attrs: AttributeStore, minCount: Int, perCell: Int, maxCells: Int, samplePerCell: Int, iters: Int, threads: Int, log: (String) -> Unit): PerTagIvf {
            val frequent = (0 until attrs.nTags).filter { attrs.cardinality(it) >= minCount }.sortedByDescending { attrs.cardinality(it) }
            log("per-tag IVF: ${frequent.size} tags with >= $minCount items")
            val km = KMeans(store.dim, threads)
            val out = HashMap<Int, TagIndex>()
            val t0 = System.nanoTime()
            for ((n, t) in frequent.withIndex()) {
                val rows = attrs.matching(intArrayOf(t)).toArray()
                val k = (rows.size / perCell).coerceIn(1, maxCells)
                val sub = SubStore(store, rows)
                val res = km.train(sub, k, minOf(rows.size, samplePerCell * k), iters, 17L + t)
                val assign = km.assignAll(sub, 0 until rows.size, res.centroids, k)
                val buckets = Array(k) { ArrayList<Int>() }
                for (j in rows.indices) buckets[assign[j]] += rows[j]
                out[t] = TagIndex(t, k, store.dim, res.centroids, Array(k) { c -> RoaringBitmap.bitmapOf(*buckets[c].toIntArray()).also { it.runOptimize() } })
                if (n % 50 == 0) log("per-tag IVF: $n/${frequent.size} tag=$t items=${rows.size} cells=$k t=${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
            }
            km.close()
            return PerTagIvf(minCount, perCell, out)
        }
    }

    /** A view of selected rows of a store, as rows 0 until rows.size. */
    private class SubStore(private val base: VectorStore, private val rows: IntArray) : VectorStore {
        override val dim get() = base.dim
        override val size get() = rows.size
        override val isByte get() = base.isByte
        override fun dist(q: QueryVector, row: Int) = base.dist(q, rows[row])
        override fun query(row: Int) = base.query(rows[row])
    }
}

/**
 * S6: per-tag sub-index search. The query's rarest indexed tag supplies the cells, visited nearest
 * first until at least `ef` candidates passing the whole predicate have been scored (ParlayANN also
 * sizes its search by a candidate count rather than a fixed number of cells, since tags range from 10
 * to thousands of cells here). Predicates matching at most `fallbackBelow` items (exact count), or
 * with no indexed tag, go to [fallback] (S0): scanning them is cheaper than any index.
 */
class PerTagIvfStrategy(
    private val store: VectorStore,
    private val ranges: facetindex.attrs.RangeStore?,
    private val attrs: AttributeStore,
    private val index: PerTagIvf,
    private val fallback: FilterStrategy,
    private val fallbackBelow: Int = 20_000,
) : FilterStrategy {
    override val name = "S6"

    override fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult {
        val lead = p.tags.filter { it in index.indexedTags }.minByOrNull { attrs.cardinality(it) }
        if (lead == null || attrs.matchCount(p.tags) <= fallbackBelow) return fallback.search(q, p, k, budget)
        val ti = index.of(lead)!!
        val qf = when (q) { is QueryVector.U8 -> FloatArray(q.dim) { (q.v[it].toInt() and 0xFF).toFloat() }; is QueryVector.F32 -> q.v }
        val dist = FloatArray(ti.k) { L2.f32(qf, ti.centroids, it * ti.d, ti.d) }
        val order = (0 until ti.k).sortedBy { dist[it] }
        val target = maxOf(k, if (budget.ef > 0) budget.ef else 5000)
        val maxCells = if (budget.nprobe > 0) budget.nprobe else ti.k
        val others = p.tags.filter { it != lead }.toIntArray()
        val top = TopK(k)
        var scored = 0L
        val u8 = store as? U8Store
        val qb = (q as? QueryVector.U8)?.v?.let { v -> u8?.prepare(v) }
        fun score(r: Int) {
            if (p.ranges.isNotEmpty() && !ranges!!.pass(r, p.ranges)) return
            val d = if (u8 != null && qb != null) u8.distInt(qb, r).toFloat() else store.dist(q, r)
            top.offer(d, r); scored++
        }
        val leadOnly = intArrayOf(lead)
        attrs.withPostings(if (others.isEmpty()) leadOnly else others) { bms ->
            if (bms.any { it == null }) return@withPostings
            var visited = 0
            for (c in order) {
                if (scored >= target || visited >= maxCells) break
                visited++
                val cand = when {
                    others.isEmpty() -> ti.cells[c]
                    bms.size == 1 -> RoaringBitmap.and(ti.cells[c], bms[0]!!)
                    else -> RoaringBitmap.and(RoaringBitmap.and(ti.cells[c], bms[0]!!), bms[1]!!)
                }
                // Cells were built at load time; rows deleted or retagged since are dropped by the live check.
                cand.forEach { r: Int -> if (attrs.hasAll(r, leadOnly)) score(r) }
            }
        }
        return top.result(scored)
    }
}
