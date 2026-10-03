package facetindex

import facetindex.attrs.AttributeStore
import facetindex.data.Csr
import facetindex.data.Formats
import facetindex.data.U8Store
import facetindex.index.IndexItem
import facetindex.index.IndexSchema
import facetindex.index.VectorIndex
import facetindex.ivf.IvfIndex
import facetindex.ivf.KMeans
import facetindex.strategy.Engine
import facetindex.strategy.SearcherSource
import org.apache.lucene.search.IndexSearcher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * A small generated filtered dataset: clustered uint8 vectors and Zipf-like tags, some of them
 * correlated with the vector's cluster (as YFCC's are with image content).
 */
class Fixture(val n: Int = 4000, val d: Int = 32, val nTags: Int = 60, seed: Int = 11, val clusters: Int = 32) : AutoCloseable {
    val dir: Path = Files.createTempDirectory("facetindex-fixture")
    val rnd = Random(seed)
    private val centers = Array(8) { ByteArray(d) { rnd.nextInt(30, 220).toByte() } }
    val rowsTags: List<IntArray>
    val store: U8Store
    val tags: Csr
    val attrs: AttributeStore
    val ivf: IvfIndex
    val index: VectorIndex

    init {
        val vecs = Array(n) { i ->
            val c = centers[i % centers.size]
            ByteArray(d) { j -> ((c[j].toInt() and 0xFF) + rnd.nextInt(-25, 26)).coerceIn(0, 255).toByte() }
        }
        Formats.writeU8bin(dir.resolve("base.u8bin"), n, d) { vecs[it] }
        store = U8Store(Formats.readU8Matrix(dir.resolve("base.u8bin")))
        rowsTags = List(n) { i ->
            val s = HashSet<Int>()
            s += i % centers.size // correlated with the cluster
            repeat(rnd.nextInt(0, 6)) { s += zipf() }
            s.sorted().toIntArray()
        }
        tags = Csr.of(rowsTags, nTags)
        attrs = AttributeStore(nTags, n).also { it.load(tags) }
        val km = KMeans(d, 2)
        val res = km.train(store, clusters, n, 8, 3)
        val assign = km.assignAll(store, 0 until n, res.centroids, clusters)
        km.close()
        ivf = IvfIndex(clusters, d, res.centroids, assign)
        index = VectorIndex(dir.resolve("idx"), IndexSchema(clusterFields = listOf(VectorIndex.clusterField(clusters))), create = true, refreshMs = 0)
        for (r in 0 until n) index.add(IndexItem(r, store.luceneBytes(r), tags = rowsTags[r], clusters = mapOf(VectorIndex.clusterField(clusters) to assign[r])))
        index.commit()
        index.refreshNow()
    }

    private fun zipf(): Int {
        // P(t) proportional to 1 / (t + 1), over tags 8 until nTags.
        val h = (8 until nTags).sumOf { 1.0 / (it - 7) }
        var u = rnd.nextDouble() * h
        for (t in 8 until nTags) { u -= 1.0 / (t - 7); if (u <= 0) return t }
        return nTags - 1
    }

    val overlay = facetindex.data.OverlayStore(store)

    fun engine(): Engine = Engine(overlay, attrs, object : SearcherSource {
        override fun <T> withSearcher(body: (IndexSearcher) -> T): T = index.withSearcher(body)
    }, mapOf(clusters to ivf))

    /** Exact filtered top-k by a plain scalar loop (the reference every strategy is checked against). */
    fun bruteForce(q: ByteArray, required: IntArray, k: Int): List<Int> =
        (0 until n).filter { r -> attrs.isLive(r) && required.all { it in attrs.tagsOf(r) } }
            .sortedWith(compareBy<Int>({ facetindex.simd.L2.u8(q, store.m.row(it)) }, { it }))
            .take(k)

    fun randomQuery(): ByteArray = store.m.row(rnd.nextInt(n)).also { v -> for (i in v.indices) v[i] = ((v[i].toInt() and 0xFF) + rnd.nextInt(-10, 11)).coerceIn(0, 255).toByte() }

    override fun close() {
        index.closeAndDelete()
        facetindex.util.deleteRecursively(dir)
    }
}
