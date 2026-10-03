package facetindex.data

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Readers and writers for the big-ann-benchmarks file formats. All little endian.
 *
 * - `.u8bin` / `.fbin` / `.i8bin`: int32 n, int32 d, then n * d elements (uint8, float32, int8).
 * - `.ibin` (ground truth): int32 nq, int32 k, then nq * k int32 ids, then nq * k float32 distances.
 * - `.spmat` (CSR): int64 nrow, int64 ncol, int64 nnz, then int64 indptr[nrow + 1], int32
 *   indices[nnz], float32 data[nnz].
 */
object Formats {
    private val LE_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    private val LE_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)
    private val LE_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN)

    /** Maps a whole file read-only into a global arena (lives until the JVM exits). */
    fun map(path: Path): MemorySegment = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
        ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), Arena.global())
    }

    fun header(path: Path): Pair<Int, Int> = FileChannel.open(path, StandardOpenOption.READ).use { ch ->
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        ch.read(b, 0); b.flip()
        b.int to b.int
    }

    fun readIbin(path: Path): GroundTruth {
        val seg = map(path)
        val nq = seg.get(LE_INT, 0)
        val k = seg.get(LE_INT, 4)
        val n = nq.toLong() * k
        require(seg.byteSize() == 8 + n * 8) { "$path: size ${seg.byteSize()} does not match nq=$nq k=$k" }
        val ids = IntArray(n.toInt())
        val dists = FloatArray(n.toInt())
        MemorySegment.copy(seg, LE_INT, 8, ids, 0, ids.size)
        MemorySegment.copy(seg, LE_FLOAT, 8 + n * 4, dists, 0, dists.size)
        return GroundTruth(nq, k, ids, dists)
    }

    fun writeIbin(path: Path, gt: GroundTruth) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val buf = ByteBuffer.allocate(8 + gt.ids.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(gt.nq).putInt(gt.k)
        gt.ids.forEach { buf.putInt(it) }
        gt.dists.forEach { buf.putFloat(it) }
        Files.write(path, buf.array())
    }

    fun readSpmat(path: Path): Csr {
        val seg = map(path)
        val nrow = seg.get(LE_LONG, 0)
        val ncol = seg.get(LE_LONG, 8)
        val nnz = seg.get(LE_LONG, 16)
        require(nrow < Int.MAX_VALUE && nnz < Int.MAX_VALUE) { "$path too large: nrow=$nrow nnz=$nnz" }
        val indptr = LongArray((nrow + 1).toInt())
        MemorySegment.copy(seg, LE_LONG, 24, indptr, 0, indptr.size)
        require(indptr.last() == nnz) { "$path: indptr end ${indptr.last()} != nnz $nnz" }
        val indices = IntArray(nnz.toInt())
        MemorySegment.copy(seg, LE_INT, 24 + (nrow + 1) * 8, indices, 0, indices.size)
        val offsets = IntArray(indptr.size) { indptr[it].toInt() }
        return Csr(nrow.toInt(), ncol.toInt(), offsets, indices)
    }

    /** Writes a CSR with every data value 1.0f (the benchmark's tag matrices are 0/1). */
    fun writeSpmat(path: Path, csr: Csr) {
        Files.createDirectories(path.toAbsolutePath().parent)
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(path), 1 shl 20)).use { out ->
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            fun long(v: Long) { b.clear(); b.putLong(v); out.write(b.array(), 0, 8) }
            fun int(v: Int) { b.clear(); b.putInt(v); out.write(b.array(), 0, 4) }
            fun float(v: Float) { b.clear(); b.putFloat(v); out.write(b.array(), 0, 4) }
            long(csr.nrow.toLong()); long(csr.ncol.toLong()); long(csr.nnz.toLong())
            csr.offsets.forEach { long(it.toLong()) }
            for (i in 0 until csr.nnz) int(csr.indices[i])
            repeat(csr.nnz) { float(1f) }
        }
    }

    /** Writes an n x d uint8 matrix (stored in [data] row-major) as `.u8bin`. */
    fun writeU8bin(path: Path, n: Int, d: Int, rows: (Int) -> ByteArray) {
        Files.createDirectories(path.toAbsolutePath().parent)
        BufferedOutputStream(Files.newOutputStream(path), 1 shl 20).use { out ->
            val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(n).putInt(d)
            out.write(h.array())
            for (i in 0 until n) out.write(rows(i))
        }
    }

    fun readU8Matrix(path: Path): U8Matrix {
        val seg = map(path)
        val n = seg.get(LE_INT, 0)
        val d = seg.get(LE_INT, 4)
        require(seg.byteSize() == 8L + n.toLong() * d) { "$path: size ${seg.byteSize()} does not match n=$n d=$d" }
        return U8Matrix(n, d, seg.asSlice(8))
    }

    fun readF32Matrix(path: Path, limit: Int = Int.MAX_VALUE): F32Matrix {
        val seg = map(path)
        val n = seg.get(LE_INT, 0)
        val d = seg.get(LE_INT, 4)
        require(seg.byteSize() == 8L + n.toLong() * d * 4) { "$path: size ${seg.byteSize()} does not match n=$n d=$d" }
        return F32Matrix(minOf(n, limit), d, seg.asSlice(8))
    }

    fun intAt(seg: MemorySegment, off: Long) = seg.get(LE_INT, off)
    fun floatAt(seg: MemorySegment, off: Long) = seg.get(LE_FLOAT, off)
    val floatLayout: ValueLayout.OfFloat get() = LE_FLOAT
}

/** Exact neighbours per query. Missing entries (fewer than k matches) are id -1. */
class GroundTruth(val nq: Int, val k: Int, val ids: IntArray, val dists: FloatArray) {
    fun row(q: Int): IntArray = ids.copyOfRange(q * k, (q + 1) * k)
    fun distRow(q: Int): FloatArray = dists.copyOfRange(q * k, (q + 1) * k)

    /**
     * Recall@[count] for one query, mirroring big-ann-benchmarks' compute_recall_with_distance_ties:
     * the true set is extended past position count - 1 with every GT entry whose distance ties the
     * count-th (within 1e-6), then |true set ∩ result| is divided by count by the caller.
     */
    fun hits(q: Int, result: IntArray, count: Int = 10): Int {
        val base = q * k
        var end = k
        if (k != count) {
            val anchor = dists[base + count - 1]
            end = k
            for (i in count until k) if (Math.abs(anchor - dists[base + i]) >= 1e-6f) { end = i; break }
        }
        val truth = HashSet<Int>(end * 2)
        for (i in 0 until end) truth += ids[base + i]
        var h = 0
        val seen = HashSet<Int>()
        for (r in result) if (r in truth && seen.add(r)) h++
        return h
    }

    /** Number of real (non -1) neighbours stored for a query. */
    fun nValid(q: Int): Int = (0 until k).count { ids[q * k + it] >= 0 }
}

/** CSR sparse 0/1 matrix: row i has columns indices[offsets[i] until offsets[i + 1]], sorted. */
class Csr(val nrow: Int, val ncol: Int, val offsets: IntArray, val indices: IntArray) {
    val nnz: Int get() = offsets[nrow]
    fun row(i: Int): IntArray = indices.copyOfRange(offsets[i], offsets[i + 1])
    fun rowLength(i: Int) = offsets[i + 1] - offsets[i]

    /** Column counts (tag frequencies). */
    fun columnCounts(): IntArray {
        val c = IntArray(ncol)
        for (i in 0 until nnz) c[indices[i]]++
        return c
    }

    /** Sub-matrix of the given rows, in that order. */
    fun selectRows(rows: IntArray): Csr {
        val off = IntArray(rows.size + 1)
        for (j in rows.indices) off[j + 1] = off[j] + rowLength(rows[j])
        val idx = IntArray(off[rows.size])
        for (j in rows.indices) System.arraycopy(indices, offsets[rows[j]], idx, off[j], rowLength(rows[j]))
        return Csr(rows.size, ncol, off, idx)
    }

    companion object {
        fun of(rows: List<IntArray>, ncol: Int): Csr {
            val off = IntArray(rows.size + 1)
            for (i in rows.indices) off[i + 1] = off[i] + rows[i].size
            val idx = IntArray(off.last())
            for (i in rows.indices) System.arraycopy(rows[i], 0, idx, off[i], rows[i].size)
            return Csr(rows.size, ncol, off, idx)
        }
    }
}

/** n x d uint8 vectors backed by a mapped file (no heap copy). */
class U8Matrix(val n: Int, val d: Int, val seg: MemorySegment) {
    fun row(i: Int): ByteArray {
        val out = ByteArray(d)
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, i.toLong() * d, out, 0, d)
        return out
    }
}

/** n x d float32 vectors backed by a mapped file. */
class F32Matrix(val n: Int, val d: Int, val seg: MemorySegment) {
    fun row(i: Int): FloatArray {
        val out = FloatArray(d)
        MemorySegment.copy(seg, Formats.floatLayout, i.toLong() * d * 4, out, 0, d)
        return out
    }
}
