package facetindex.ivf

import facetindex.data.VectorStore
import facetindex.data.QueryVector
import facetindex.simd.L2
import java.util.SplittableRandom
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Lloyd's k-means with k-means++ seeding, written for this project (no library), over a random sample
 * of the base vectors. The assignment step is blocked: 64 points against 256 centroids at a time so
 * that the centroid tile stays in the L2 cache, using ||x - c||^2 = ||x||^2 - 2 x.c + ||c||^2 and
 * SIMD dot products. Empty clusters are re-seeded by splitting the largest one.
 */
class KMeans(private val d: Int, private val threads: Int = Runtime.getRuntime().availableProcessors()) {
    private val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "kmeans").apply { isDaemon = true } }

    class Result(val centroids: FloatArray, val k: Int, val d: Int, val iterations: Int, val sampleSize: Int, val inertiaPerIter: List<Double>, val seconds: Double)

    /** Converts rows of a store to a dense float matrix. */
    fun toFloats(store: VectorStore, rows: IntArray): FloatArray {
        val out = FloatArray(rows.size * d)
        parallelFor(rows.size) { lo, hi ->
            for (i in lo until hi) {
                val q = store.query(rows[i])
                when (q) {
                    is QueryVector.U8 -> for (j in 0 until d) out[i * d + j] = (q.v[j].toInt() and 0xFF).toFloat()
                    is QueryVector.F32 -> System.arraycopy(q.v, 0, out, i * d, d)
                }
            }
        }
        return out
    }

    fun train(store: VectorStore, k: Int, sampleSize: Int, iterations: Int, seed: Long, log: (String) -> Unit = {}): Result {
        val t0 = System.nanoTime()
        val rnd = SplittableRandom(seed)
        val n = store.size
        val m = minOf(sampleSize, n)
        // Sample without replacement (partial Fisher-Yates over a lazily materialized permutation).
        val sample = sampleRows(n, m, rnd)
        val x = toFloats(store, sample)
        log("kmeans: k=$k sample=$m d=$d, seeding")
        val c = seedPlusPlus(x, m, k, rnd)
        val inertia = ArrayList<Double>()
        val assign = IntArray(m)
        for (it in 0 until iterations) {
            val total = assignBlocked(x, m, c, k, assign, null)
            inertia += total
            update(x, m, c, k, assign, rnd)
            log("kmeans: iter ${it + 1}/$iterations inertia/point=${"%.1f".format(total / m)} t=${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
        }
        return Result(c, k, d, iterations, m, inertia, (System.nanoTime() - t0) / 1e9)
    }

    private fun sampleRows(n: Int, m: Int, rnd: SplittableRandom): IntArray {
        if (m == n) return IntArray(n) { it }
        val chosen = java.util.BitSet(n)
        val out = IntArray(m)
        var i = 0
        while (i < m) {
            val r = rnd.nextInt(n)
            if (!chosen.get(r)) { chosen.set(r); out[i++] = r }
        }
        out.sort()
        return out
    }

    private fun seedPlusPlus(x: FloatArray, m: Int, k: Int, rnd: SplittableRandom): FloatArray {
        val c = FloatArray(k * d)
        val minD = FloatArray(m) { Float.POSITIVE_INFINITY }
        var pick = rnd.nextInt(m)
        val partial = DoubleArray(threads)
        for (j in 0 until k) {
            System.arraycopy(x, pick * d, c, j * d, d)
            val cj = j * d
            // Update min distances and their sum in parallel chunks.
            val chunk = (m + threads - 1) / threads
            val fs = (0 until threads).map { t ->
                pool.submit {
                    var s = 0.0
                    val lo = t * chunk; val hi = minOf(m, lo + chunk)
                    for (i in lo until hi) {
                        val dd = l2(x, i * d, c, cj)
                        if (dd < minD[i]) minD[i] = dd
                        s += minD[i]
                    }
                    partial[t] = s
                }
            }
            fs.forEach { it.get() }
            if (j == k - 1) break
            val total = partial.sum()
            var target = rnd.nextDouble() * total
            pick = m - 1
            for (i in 0 until m) {
                target -= minD[i]
                if (target <= 0) { pick = i; break }
            }
        }
        return c
    }

    private fun l2(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Float = L2.f32(a, ao, b, bo, d)

    // The Vector API only compiles to SIMD when the species is a static final constant, so the
    // kernels live in Java (BUG_LOG #3).
    private fun dot(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Float = L2.dot(a, ao, b, bo, d)

    /**
     * Assigns each of the m points in [x] to its nearest centroid (into [assign]) and returns the
     * total squared distance. [dists], if given, receives each point's squared distance.
     */
    fun assignBlocked(x: FloatArray, m: Int, c: FloatArray, k: Int, assign: IntArray, dists: FloatArray?): Double {
        val cn = FloatArray(k) { j -> dot(c, j * d, c, j * d) }
        val pb = 64; val cb = 256
        val blocks = (m + pb - 1) / pb
        val partial = DoubleArray(blocks)
        parallelFor(blocks) { lo, hi ->
            val best = FloatArray(pb)
            val bestIdx = IntArray(pb)
            for (b in lo until hi) {
                val p0 = b * pb; val p1 = minOf(m, p0 + pb)
                java.util.Arrays.fill(best, Float.POSITIVE_INFINITY)
                var c0 = 0
                while (c0 < k) {
                    val c1 = minOf(k, c0 + cb)
                    for (p in p0 until p1) {
                        var bv = best[p - p0]; var bi = bestIdx[p - p0]
                        val po = p * d
                        for (j in c0 until c1) {
                            val v = cn[j] - 2f * dot(x, po, c, j * d)
                            if (v < bv) { bv = v; bi = j }
                        }
                        best[p - p0] = bv; bestIdx[p - p0] = bi
                    }
                    c0 = c1
                }
                var s = 0.0
                for (p in p0 until p1) {
                    assign[p] = bestIdx[p - p0]
                    val xn = dot(x, p * d, x, p * d)
                    val dd = maxOf(0f, xn + best[p - p0])
                    dists?.set(p, dd)
                    s += dd
                }
                partial[b] = s
            }
        }
        return partial.sum()
    }

    private fun update(x: FloatArray, m: Int, c: FloatArray, k: Int, assign: IntArray, rnd: SplittableRandom) {
        val sum = DoubleArray(k * d)
        val cnt = IntArray(k)
        for (i in 0 until m) {
            val a = assign[i]; cnt[a]++
            val xo = i * d; val so = a * d
            for (j in 0 until d) sum[so + j] += x[xo + j]
        }
        for (a in 0 until k) if (cnt[a] > 0) for (j in 0 until d) c[a * d + j] = (sum[a * d + j] / cnt[a]).toFloat()
        // Split the largest cluster for each empty one (as FAISS does), with a small symmetric nudge.
        for (a in 0 until k) if (cnt[a] == 0) {
            var big = 0
            for (b in 0 until k) if (cnt[b] > cnt[big]) big = b
            for (j in 0 until d) {
                val eps = (1f / 1024) * (if (j % 2 == 0) 1 else -1)
                c[a * d + j] = c[big * d + j] * (1 + eps)
                c[big * d + j] = c[big * d + j] * (1 - eps)
            }
            cnt[a] = cnt[big] / 2; cnt[big] -= cnt[a]
        }
    }

    /** Assigns every row of [store] (in chunks, to bound memory) to its nearest centroid. */
    fun assignAll(store: VectorStore, rows: IntRange, c: FloatArray, k: Int, log: (String) -> Unit = {}): IntArray {
        val out = IntArray(rows.last + 1) { -1 }
        val chunk = 262_144
        var lo = rows.first
        val t0 = System.nanoTime()
        while (lo <= rows.last) {
            val hi = minOf(rows.last + 1, lo + chunk)
            val ids = IntArray(hi - lo) { lo + it }
            val x = toFloats(store, ids)
            val a = IntArray(ids.size)
            assignBlocked(x, ids.size, c, k, a, null)
            System.arraycopy(a, 0, out, lo, a.size)
            lo = hi
            if ((lo / chunk) % 8 == 0) log("assign: $lo / ${rows.last + 1} t=${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
        }
        return out
    }

    private fun parallelFor(n: Int, body: (Int, Int) -> Unit) {
        if (n == 0) return
        val parts = minOf(n, threads * 4)
        val step = (n + parts - 1) / parts
        val fs = ArrayList<Future<*>>()
        var lo = 0
        while (lo < n) {
            val a = lo; val b = minOf(n, lo + step)
            fs += pool.submit { body(a, b) }
            lo = b
        }
        fs.forEach { it.get() }
    }

    fun close() = pool.shutdownNow()
}
