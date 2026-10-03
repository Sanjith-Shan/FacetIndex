package facetindex.data

import facetindex.simd.L2
import java.lang.foreign.MemorySegment
import java.nio.file.Files
import java.nio.file.Path

/** A query vector: uint8 (YFCC) or float32 (MS Turing, arXiv). */
sealed interface QueryVector {
    val dim: Int

    class U8(val v: ByteArray) : QueryVector {
        override val dim get() = v.size
    }

    class F32(val v: FloatArray) : QueryVector {
        override val dim get() = v.size
    }
}

/**
 * Base vectors addressed by row id. Rows are the dataset's own ids (YFCC: 0 until 10M), so ground
 * truth ids, bitmap entries and Lucene's `row` doc value all use one id space.
 */
interface VectorStore {
    val dim: Int
    val size: Int
    val isByte: Boolean

    /** Squared L2 distance from the query to a row. */
    fun dist(q: QueryVector, row: Int): Float

    fun query(row: Int): QueryVector

    /** The row as Lucene stores it: uint8 shifted to int8 (x xor 0x80), which preserves L2. */
    fun luceneBytes(row: Int): ByteArray = throw UnsupportedOperationException()
    fun luceneFloats(row: Int): FloatArray = throw UnsupportedOperationException()
}

class U8Store(val m: U8Matrix) : VectorStore {
    override val dim get() = m.d
    override val size get() = m.n
    override val isByte get() = true
    private val seg: MemorySegment = m.seg

    override fun dist(q: QueryVector, row: Int): Float = L2.u8((q as QueryVector.U8).v, seg, row.toLong() * m.d, m.d).toFloat()

    fun distInt(q: ByteArray, row: Int): Int = L2.u8(q, seg, row.toLong() * m.d, m.d)

    override fun query(row: Int) = QueryVector.U8(m.row(row))

    override fun luceneBytes(row: Int): ByteArray = m.row(row).also { toLuceneBytes(it) }

    companion object {
        /** In place: uint8 to Lucene's signed int8 by flipping the top bit (x - 128). */
        fun toLuceneBytes(v: ByteArray): ByteArray {
            for (i in v.indices) v[i] = (v[i].toInt() xor 0x80).toByte()
            return v
        }
    }
}

class F32Store(val m: F32Matrix) : VectorStore {
    override val dim get() = m.d
    override val size get() = m.n
    override val isByte get() = false

    override fun dist(q: QueryVector, row: Int): Float = L2.f32((q as QueryVector.F32).v, m.seg, row.toLong() * m.d * 4, m.d)
    override fun query(row: Int) = QueryVector.F32(m.row(row))
    override fun luceneFloats(row: Int): FloatArray = m.row(row)
}

/** The query side of a filtered dataset: vectors, tag lists, ground truth. */
class QuerySet(val name: String, val vectors: U8Matrix, val tags: Csr, val gt: GroundTruth?) {
    val n get() = vectors.n
    fun vector(i: Int) = QueryVector.U8(vectors.row(i))
    fun tagsOf(i: Int): IntArray = tags.row(i)
}

/**
 * A YFCC-style filtered dataset on disk. [dir] holds the benchmark's file names; a slice directory
 * written by `facetindex slice` uses the same names, so every command runs on either.
 */
class FilteredDataset(val dir: Path, val name: String) {
    val baseFile: Path = dir.resolve("base.10M.u8bin").takeIf { Files.exists(it) } ?: dir.resolve("base.u8bin")
    val baseMetaFile: Path = dir.resolve("base.metadata.10M.spmat").takeIf { Files.exists(it) } ?: dir.resolve("base.metadata.spmat")

    val base: U8Store by lazy { U8Store(Formats.readU8Matrix(baseFile)) }
    val baseTags: Csr by lazy { Formats.readSpmat(baseMetaFile) }

    fun queries(which: String): QuerySet {
        val (q, m, g) = when (which) {
            "public" -> Triple("query.public.100K.u8bin", "query.metadata.public.100K.spmat", "GT.public.ibin")
            "private" -> Triple("query.private.$PRIVATE_KEY.100K.u8bin", "query.metadata.private.$PRIVATE_KEY.100K.spmat", "GT.private.$PRIVATE_KEY.ibin")
            else -> throw IllegalArgumentException("query set '$which'")
        }
        val gtPath = dir.resolve(g)
        return QuerySet(
            which, Formats.readU8Matrix(dir.resolve(q)), Formats.readSpmat(dir.resolve(m)),
            if (Files.exists(gtPath)) Formats.readIbin(gtPath) else null,
        )
    }

    companion object {
        const val PRIVATE_KEY = 2727415019L
    }
}

/**
 * A base store plus vectors that arrived after it was written (inserts through the API or the
 * stream whose row is outside the base file, or whose vector differs from the file's). Lookups go to
 * the overlay only for rows it holds, so a store with an empty overlay costs one map probe per call.
 */
class OverlayStore(val base: VectorStore) : VectorStore {
    private val overlay = java.util.concurrent.ConcurrentHashMap<Int, QueryVector>()
    override val dim get() = base.dim
    override val size get() = maxOf(base.size, (overlay.keys.maxOrNull() ?: -1) + 1)
    override val isByte get() = base.isByte

    fun put(row: Int, v: QueryVector) {
        if (row < base.size) {
            val same = when (v) {
                is QueryVector.U8 -> (base.query(row) as? QueryVector.U8)?.v?.contentEquals(v.v) == true
                is QueryVector.F32 -> (base.query(row) as? QueryVector.F32)?.v?.contentEquals(v.v) == true
            }
            if (same) { overlay.remove(row); return }
        }
        overlay[row] = v
    }

    private fun direct(q: QueryVector, v: QueryVector): Float = when (q) {
        is QueryVector.U8 -> L2.u8(q.v, (v as QueryVector.U8).v).toFloat()
        is QueryVector.F32 -> { val b = (v as QueryVector.F32).v; var s = 0f; for (i in b.indices) { val d = q.v[i] - b[i]; s += d * d }; s }
    }

    override fun dist(q: QueryVector, row: Int): Float {
        if (overlay.isEmpty()) return base.dist(q, row)
        val v = overlay[row] ?: return base.dist(q, row)
        return direct(q, v)
    }

    override fun query(row: Int): QueryVector = overlay[row] ?: base.query(row)
    override fun luceneBytes(row: Int): ByteArray = (overlay[row] as? QueryVector.U8)?.v?.copyOf()?.let { U8Store.toLuceneBytes(it) } ?: base.luceneBytes(row)
    override fun luceneFloats(row: Int): FloatArray = (overlay[row] as? QueryVector.F32)?.v ?: base.luceneFloats(row)
}
