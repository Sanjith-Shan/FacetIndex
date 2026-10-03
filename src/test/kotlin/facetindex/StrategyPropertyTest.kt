package facetindex

import facetindex.data.QueryVector
import facetindex.strategy.IvfAsLuceneFilter
import facetindex.strategy.IvfIntersect
import facetindex.strategy.LuceneFilteredHnsw
import facetindex.strategy.PostFilterHnsw
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Every strategy, on every generated query: no result may violate the predicate. S0 must equal brute
 * force exactly, and S4 / S4-L with every cluster probed must too. Graph strategies must reach a
 * sane recall at a generous beam.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StrategyPropertyTest {
    private val fx = Fixture()
    private val e = fx.engine()
    private val k = 10

    private fun queries(count: Int): List<Pair<ByteArray, IntArray>> = List(count) {
        val q = fx.randomQuery()
        // One or two tags, drawn from tags some row actually carries.
        val row = fx.rnd.nextInt(fx.n)
        val t = fx.rowsTags[row]
        val tags = if (t.size >= 2 && fx.rnd.nextBoolean()) intArrayOf(t[0], t[t.size - 1]) else intArrayOf(t[fx.rnd.nextInt(t.size)])
        q to tags
    }

    @AfterAll
    fun close() = fx.close()

    @Test
    fun `no strategy ever returns a row that fails the predicate`() {
        val strategies = listOf(
            PreFilterBruteForce(e) to SearchBudget(),
            PostFilterHnsw(e) to SearchBudget(ef = 50, safety = 2.0),
            LuceneFilteredHnsw(e, "S2", 0) to SearchBudget(ef = 40),
            LuceneFilteredHnsw(e, "S3", 60) to SearchBudget(ef = 40),
            IvfIntersect(e, fx.clusters) to SearchBudget(nprobe = 4),
            IvfAsLuceneFilter(e, fx.clusters) to SearchBudget(nprobe = 4),
        )
        var violations = 0
        for ((q, tags) in queries(300)) for ((s, b) in strategies) {
            val r = s.search(QueryVector.U8(q), Predicate(tags), k, b)
            for (row in r.rows) if (!tags.all { it in fx.rowsTags[row] }) violations++
            assertTrue(r.rows.toSet().size == r.rows.size, "${s.name} returned duplicates")
        }
        assertEquals(0, violations)
    }

    @Test
    fun `S0 equals brute force exactly on every query`() {
        val s0 = PreFilterBruteForce(e)
        for ((q, tags) in queries(300)) {
            val exp = fx.bruteForce(q, tags, k)
            val got = s0.search(QueryVector.U8(q), Predicate(tags), k, SearchBudget()).rows.toList()
            assertEquals(exp, got)
        }
    }

    @Test
    fun `IVF with every cluster probed is exact, in both forms`() {
        val s4 = IvfIntersect(e, fx.clusters)
        val s4l = IvfAsLuceneFilter(e, fx.clusters)
        for ((q, tags) in queries(150)) {
            val exp = fx.bruteForce(q, tags, k)
            assertEquals(exp, s4.search(QueryVector.U8(q), Predicate(tags), k, SearchBudget(nprobe = fx.clusters)).rows.toList())
            // S4-L ranks by Lucene's float score, so compare as sets (ties may order differently).
            assertEquals(exp.toSet(), s4l.search(QueryVector.U8(q), Predicate(tags), k, SearchBudget(nprobe = fx.clusters)).rows.toSet())
        }
    }

    @Test
    fun `Lucene filtered HNSW reaches high recall with a wide beam`() {
        for ((name, thr) in listOf("S2" to 0, "S3" to 60)) {
            val s = LuceneFilteredHnsw(e, name, thr)
            var hits = 0; var total = 0
            for ((q, tags) in queries(200)) {
                val exp = fx.bruteForce(q, tags, k).toSet()
                hits += s.search(QueryVector.U8(q), Predicate(tags), k, SearchBudget(ef = 100)).rows.count { it in exp }
                total += exp.size
            }
            assertTrue(hits >= 0.95 * total, "$name recall ${hits.toDouble() / total}")
        }
    }

    @Test
    fun `external filter sees attribute changes with no index refresh`() {
        val s2 = LuceneFilteredHnsw(e, "S2", 0)
        val (q, _) = queries(1).first()
        val tag = 1
        val before = s2.search(QueryVector.U8(q), Predicate(intArrayOf(tag)), k, SearchBudget(ef = 50)).rows
        val victim = before.first()
        fx.attrs.setAttrs(victim, IntArray(0), intArrayOf(tag))
        val after = s2.search(QueryVector.U8(q), Predicate(intArrayOf(tag)), k, SearchBudget(ef = 50)).rows
        assertTrue(victim !in after, "removed tag still matched")
        fx.attrs.setAttrs(victim, intArrayOf(tag), IntArray(0))
        val again = s2.search(QueryVector.U8(q), Predicate(intArrayOf(tag)), k, SearchBudget(ef = 50)).rows
        assertTrue(victim in again)
    }
}
