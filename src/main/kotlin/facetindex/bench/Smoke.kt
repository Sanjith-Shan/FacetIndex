package facetindex.bench

import facetindex.attrs.AttributeStore
import facetindex.data.Csr
import facetindex.data.Formats
import facetindex.data.QueryVector
import facetindex.data.U8Store
import facetindex.index.IndexItem
import facetindex.index.IndexSchema
import facetindex.index.VectorIndex
import facetindex.ivf.IvfIndex
import facetindex.ivf.KMeans
import facetindex.strategy.Engine
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.Predicate
import facetindex.strategy.SearcherSource
import facetindex.util.Args
import facetindex.util.deleteRecursively
import org.apache.lucene.search.IndexSearcher
import java.nio.file.Files
import java.util.SplittableRandom

/**
 * CI smoke run: a generated dataset (clustered uint8 vectors, Zipf tags partly correlated with the
 * cluster), every strategy on N queries against exact brute force. Fails if any result violates its
 * predicate, if S0 is not exact, or if a strategy's recall falls below a loose floor.
 */
object Smoke {
    fun run(a: Args): Int {
        val n = a.int("n", 100_000)
        val d = a.int("d", 64)
        val nTags = a.int("tags", 2000)
        val nq = a.int("queries", 1000)
        val dir = a.path("dir")
        deleteRecursively(dir); Files.createDirectories(dir)
        val rnd = SplittableRandom(42)
        val centers = Array(64) { ByteArray(d) { (30 + rnd.nextInt(190)).toByte() } }
        fun vec(c: Int) = ByteArray(d) { j -> ((centers[c][j].toInt() and 0xFF) + rnd.nextInt(51) - 25).coerceIn(0, 255).toByte() }
        val cl = IntArray(n) { rnd.nextInt(centers.size) }
        Formats.writeU8bin(dir.resolve("base.u8bin"), n, d) { vec(cl[it]) }
        val store = U8Store(Formats.readU8Matrix(dir.resolve("base.u8bin")))
        val zipfH = (1..nTags).sumOf { 1.0 / it }
        fun zipf(): Int { var u = rnd.nextDouble() * zipfH; for (t in 1..nTags) { u -= 1.0 / t; if (u <= 0) return t - 1 }; return nTags - 1 }
        val rows = List(n) { i -> (setOf(cl[i] % nTags) + List(rnd.nextInt(1, 8)) { zipf() }).sorted().toIntArray() }
        val attrs = AttributeStore(nTags, n).also { it.load(Csr.of(rows, nTags)) }
        val c = a.int("clusters", 256)
        val km = KMeans(d, Runtime.getRuntime().availableProcessors())
        val res = km.train(store, c, minOf(n, 64 * c), 8, 1)
        val assign = km.assignAll(store, 0 until n, res.centroids, c)
        km.close()
        val ivf = IvfIndex(c, d, res.centroids, assign)
        val idx = VectorIndex(dir.resolve("idx"), IndexSchema(clusterFields = listOf(VectorIndex.clusterField(c))), create = true, refreshMs = 0, mergeWorkers = 2)
        for (r in 0 until n) idx.add(IndexItem(r, store.luceneBytes(r), tags = rows[r], clusters = mapOf(VectorIndex.clusterField(c) to assign[r])))
        idx.commit(); idx.forceMerge(1); idx.refreshNow()
        val e = Engine(store, attrs, object : SearcherSource {
            override fun <T> withSearcher(body: (IndexSearcher) -> T): T = idx.withSearcher(body)
        }, mapOf(c to ivf))
        // Queries: perturbed base vectors with one or two tags taken from a random row.
        val qs = List(nq) {
            val r = rnd.nextInt(n)
            val v = store.m.row(rnd.nextInt(n)).also { q -> for (i in q.indices) q[i] = ((q[i].toInt() and 0xFF) + rnd.nextInt(21) - 10).coerceIn(0, 255).toByte() }
            val t = rows[r]
            v to (if (t.size >= 2 && rnd.nextBoolean()) intArrayOf(t[0], t[1]) else intArrayOf(t[rnd.nextInt(t.size)]))
        }
        // Independent exact answer: scalar loop over the row tag lists (not the bitmaps).
        val truth = qs.map { (q, tags) ->
            (0 until n).filter { r -> tags.all { java.util.Arrays.binarySearch(rows[r], it) >= 0 } }
                .sortedWith(compareBy<Int>({ facetindex.simd.L2.u8(q, store.m.row(it)) }, { it })).take(10).toSet()
        }
        val configs = listOf(
            "S0" to 1.0, "S1:safety=3,ef=100" to 0.3, "S2:ef=100" to 0.85, "S3:ef=100,threshold=60" to 0.85,
            "S2:ef=100,filter=terms" to 0.85, "S2:ef=100,filter=docvalues" to 0.85,
            "S4:c=$c,nprobe=32" to 0.85, "S4L:c=$c,nprobe=32" to 0.85,
        )
        var failed = false
        println("%-28s %8s %10s %10s".format("config", "recall", "violations", "ms/query"))
        for ((cfg, floor) in configs) {
            val spec = StrategySpec.parse(cfg)
            val s = strategyFor(e, spec)
            var hits = 0; var total = 0; var viol = 0
            val t0 = System.nanoTime()
            for ((i, qt) in qs.withIndex()) {
                val (q, tags) = qt
                val r = s.search(QueryVector.U8(q), Predicate(tags), 10, spec.budget())
                viol += r.rows.count { row -> !tags.all { java.util.Arrays.binarySearch(rows[row], it) >= 0 } }
                hits += r.rows.count { it in truth[i] }; total += truth[i].size
            }
            val ms = (System.nanoTime() - t0) / 1e6 / nq
            val recall = hits.toDouble() / total
            val ok = viol == 0 && recall >= floor && (spec.name != "S0" || recall == 1.0)
            if (!ok) failed = true
            println("%-28s %8.4f %10d %10.3f %s".format(cfg, recall, viol, ms, if (ok) "" else "FAIL (floor $floor)"))
        }
        check(PreFilterBruteForce(e).name == "S0")
        idx.close()
        deleteRecursively(dir)
        return if (failed) 1 else 0
    }
}
