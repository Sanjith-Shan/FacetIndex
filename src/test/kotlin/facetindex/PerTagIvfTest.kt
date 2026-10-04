package facetindex

import facetindex.data.QueryVector
import facetindex.ivf.PerTagIvf
import facetindex.ivf.PerTagIvfStrategy
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class PerTagIvfTest {
    @Test
    fun `S6 with every cell probed equals brute force, before and after a save and load`() {
        Fixture(n = 3000).use { fx ->
            val e = fx.engine()
            val built = PerTagIvf.build(fx.store, fx.attrs, 100, 50, 64, 32, 5, 2) {}
            val f = Files.createTempFile("pertag", ".ivf")
            built.save(f)
            for (pt in listOf(built, PerTagIvf.load(f))) {
                val s6 = PerTagIvfStrategy(fx.store, null, fx.attrs, pt, PreFilterBruteForce(e), fallbackBelow = 0)
                repeat(200) {
                    val q = fx.randomQuery()
                    val t = fx.rowsTags[fx.rnd.nextInt(fx.n)]
                    val tags = if (t.size >= 2 && fx.rnd.nextBoolean()) intArrayOf(t[0], t[t.size - 1]) else intArrayOf(t[fx.rnd.nextInt(t.size)])
                    assertEquals(fx.bruteForce(q, tags, 10), s6.search(QueryVector.U8(q), Predicate(tags), 10, SearchBudget(ef = 1_000_000)).rows.toList(), "tags ${tags.toList()}")
                }
            }
        }
    }
}
