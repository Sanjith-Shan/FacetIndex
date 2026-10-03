package facetindex.ivf

import facetindex.data.QueryVector
import facetindex.simd.L2
import org.roaringbitmap.RoaringBitmap
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The IVF structure behind S4: C centroids from [KMeans], each row's cluster, and per cluster a
 * RoaringBitmap of its live rows. It is a query-time structure beside Lucene, not a codec: a new row
 * costs one nearest-centroid search plus one bitmap add, and a delete is one bitmap remove.
 *
 * Concurrency follows the attribute store: one writer, cluster bitmaps behind striped read-write
 * locks, and readers intersect under the read lock.
 */
class IvfIndex(val k: Int, val d: Int, val centroids: FloatArray, assignment: IntArray) {
    @Volatile
    private var assign: IntArray = assignment
    private val lists = Array(k) { RoaringBitmap() }
    private val locks = Array(256) { ReentrantReadWriteLock() }

    init {
        val counts = IntArray(k)
        for (a in assign) if (a >= 0) counts[a]++
        val buckets = Array(k) { IntArray(counts[it]) }
        val fill = IntArray(k)
        for (r in assign.indices) { val a = assign[r]; if (a >= 0) buckets[a][fill[a]++] = r }
        for (c in 0 until k) { lists[c].addN(buckets[c], 0, buckets[c].size); lists[c].runOptimize() }
    }

    private fun lockOf(c: Int) = locks[c and 255]

    fun clusterOf(row: Int): Int = assign.getOrElse(row) { -1 }

    fun size(c: Int): Int = lockOf(c).read { lists[c].cardinality }

    /** The [nprobe] nearest centroids, nearest first. */
    fun probe(q: QueryVector, nprobe: Int): IntArray {
        val qf = when (q) {
            is QueryVector.U8 -> FloatArray(d) { (q.v[it].toInt() and 0xFF).toFloat() }
            is QueryVector.F32 -> q.v
        }
        return probe(qf, nprobe)
    }

    fun probe(qf: FloatArray, nprobe: Int): IntArray {
        val n = minOf(nprobe, k)
        // Max-heap of the n best so far.
        val hd = FloatArray(n); val hc = IntArray(n); var size = 0
        for (c in 0 until k) {
            val dist = L2.f32(qf, centroids, c * d, d)
            if (size < n) {
                var i = size++
                hd[i] = dist; hc[i] = c
                while (i > 0) { val p = (i - 1) / 2; if (hd[i] > hd[p]) { swap(hd, hc, i, p); i = p } else break }
            } else if (dist < hd[0]) {
                hd[0] = dist; hc[0] = c
                var i = 0
                while (true) {
                    val l = 2 * i + 1; if (l >= size) break
                    var m = l; if (l + 1 < size && hd[l + 1] > hd[l]) m = l + 1
                    if (hd[m] > hd[i]) { swap(hd, hc, m, i); i = m } else break
                }
            }
        }
        val idx = (0 until size).sortedBy { hd[it] }
        return IntArray(size) { hc[idx[it]] }
    }

    private fun swap(a: FloatArray, b: IntArray, i: Int, j: Int) {
        val t = a[i]; a[i] = a[j]; a[j] = t
        val u = b[i]; b[i] = b[j]; b[j] = u
    }

    /** Runs [body] on cluster [c]'s bitmap under its read lock. Do not keep the reference. */
    fun <T> withList(c: Int, body: (RoaringBitmap) -> T): T = lockOf(c).read { body(lists[c]) }

    // ------------------------------------------------------------------ writer

    fun nearest(q: QueryVector): Int = probe(q, 1)[0]

    /** Adds (or moves) a row into its nearest cluster. Returns the cluster. */
    fun insert(row: Int, q: QueryVector): Int {
        val c = nearest(q)
        insertAt(row, c)
        return c
    }

    fun insertAt(row: Int, c: Int) {
        if (row >= assign.size) assign = assign.copyOf(maxOf(row + 1, assign.size + assign.size / 4))
        val old = assign[row]
        if (old >= 0 && old != c) lockOf(old).write { lists[old].remove(row) }
        assign[row] = c
        lockOf(c).write { lists[c].add(row) }
    }

    fun delete(row: Int): Boolean {
        val c = clusterOf(row)
        if (c < 0) return false
        lockOf(c).write { lists[c].remove(row) }
        assign[row] = -1
        return true
    }

    fun assignmentCopy(): IntArray = assign.copyOf()

    fun bytes(): Long = centroids.size * 4L + assign.size * 4L + (0 until k).sumOf { c -> lockOf(c).read { lists[c].serializedSizeInBytes().toLong() } }

    fun save(path: Path) {
        Files.createDirectories(path.toAbsolutePath().parent)
        DataOutputStream(Files.newOutputStream(path).buffered(1 shl 20)).use { out ->
            out.writeInt(MAGIC); out.writeInt(k); out.writeInt(d)
            val a = assign
            out.writeInt(a.size)
            for (v in centroids) out.writeFloat(v)
            for (v in a) out.writeInt(v)
        }
    }

    companion object {
        private const val MAGIC = 0x1F5_0001

        fun load(path: Path): IvfIndex = DataInputStream(Files.newInputStream(path).buffered(1 shl 20)).use { inp ->
            check(inp.readInt() == MAGIC) { "$path is not an IVF file" }
            val k = inp.readInt(); val d = inp.readInt(); val n = inp.readInt()
            val c = FloatArray(k * d) { inp.readFloat() }
            val a = IntArray(n) { inp.readInt() }
            IvfIndex(k, d, c, a)
        }
    }
}
