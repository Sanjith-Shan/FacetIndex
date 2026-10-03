package facetindex.index

import org.apache.lucene.index.DocValues
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.ConstantScoreScorer
import org.apache.lucene.search.ConstantScoreWeight
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.QueryVisitor
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.ScorerSupplier
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.TwoPhaseIterator
import org.apache.lucene.search.Weight
import org.roaringbitmap.RoaringBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-segment docid to row map, read once from the `row` doc value and cached by the segment's core
 * key. Because the index is sorted by row, rows increase with docid inside a segment, so row to docid
 * is a binary search (or a subtraction when the segment's rows are contiguous).
 */
class LeafRows(val rows: IntArray) {
    val contiguous: Boolean = rows.isEmpty() || rows.last() - rows.first() == rows.size - 1
    val minRow get() = if (rows.isEmpty()) Int.MAX_VALUE else rows.first()
    val maxRow get() = if (rows.isEmpty()) Int.MIN_VALUE else rows.last()

    /** Docid of [row] in this segment, or -1. */
    fun docOf(row: Int): Int {
        if (rows.isEmpty() || row < rows.first() || row > rows.last()) return -1
        if (contiguous) return row - rows.first()
        val i = java.util.Arrays.binarySearch(rows, row)
        return if (i >= 0) i else -1
    }

    companion object {
        private val cache = ConcurrentHashMap<Any, LeafRows>()

        fun of(reader: LeafReader): LeafRows {
            val key = reader.coreCacheHelper?.key ?: return load(reader)
            return cache.computeIfAbsent(key) { k ->
                reader.coreCacheHelper.addClosedListener { cache.remove(it) }
                load(reader)
            }
        }

        private fun load(reader: LeafReader): LeafRows {
            val dv = DocValues.getNumeric(reader, VectorIndex.F_ROW)
            val rows = IntArray(reader.maxDoc())
            var prev = Int.MIN_VALUE
            for (d in 0 until reader.maxDoc()) {
                check(dv.advanceExact(d)) { "doc $d has no row" }
                val r = dv.longValue().toInt()
                check(r >= prev) { "segment is not sorted by row" }
                rows[d] = r; prev = r
            }
            return LeafRows(rows)
        }
    }
}

/** Docid iterator over the rows of a bitmap that fall in one segment, in docid order. */
private class BitmapDocIterator(private val bitmap: RoaringBitmap, private val leaf: LeafRows, private val maxDoc: Int) : DocIdSetIterator() {
    private val iter: org.roaringbitmap.PeekableIntIterator = bitmap.intIterator

    init {
        if (leaf.rows.isNotEmpty()) iter.advanceIfNeeded(leaf.minRow)
    }
    private var doc = -1
    private val estimated: Long = if (leaf.rows.isEmpty()) 0 else {
        val lo = if (leaf.minRow == 0) 0L else bitmap.rankLong(leaf.minRow - 1)
        bitmap.rankLong(leaf.maxRow) - lo
    }

    override fun docID() = doc

    override fun nextDoc(): Int {
        while (iter.hasNext()) {
            val r = iter.next()
            if (r > leaf.maxRow) break
            val d = leaf.docOf(r)
            if (d >= 0) { doc = d; return d }
        }
        doc = NO_MORE_DOCS
        return doc
    }

    override fun advance(target: Int): Int {
        if (target >= maxDoc) { doc = NO_MORE_DOCS; return doc }
        iter.advanceIfNeeded(leaf.rows[target])
        return nextDoc()
    }

    override fun cost(): Long = estimated
}

/**
 * A3 filter: documents whose row is in a bitmap computed from the external attribute store for this
 * query. Never cached, because the store changes without any change to the index. The bitmap drives
 * the iteration, so the filter's cost is the number of matching rows (which is what Lucene's filtered
 * kNN uses to choose between exact and graph search), not the segment size.
 *
 * Ported from an earlier project's availability query (a two-phase, never-cached query over a
 * docid-to-row doc value); here the external state is a tag bitmap and it leads the iteration.
 */
class RowSetQuery(val rows: RoaringBitmap, private val label: String = "rows") : Query() {
    override fun createWeight(searcher: IndexSearcher, scoreMode: ScoreMode, boost: Float): Weight =
        object : ConstantScoreWeight(this, boost) {
            override fun scorerSupplier(context: LeafReaderContext): ScorerSupplier? {
                val leaf = LeafRows.of(context.reader())
                val iter = BitmapDocIterator(rows, leaf, context.reader().maxDoc())
                if (iter.cost() == 0L) return null
                return DefaultScorerSupplier(ConstantScoreScorer(score(), scoreMode, iter))
            }

            override fun isCacheable(ctx: LeafReaderContext) = false
        }

    override fun visit(visitor: QueryVisitor) = visitor.visitLeaf(this)
    override fun toString(field: String?) = "RowSet[$label,${rows.cardinality}]"
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * A2 filter: a two-phase query over the `tagdv` binary doc value. Doc values are not an inverted
 * index, so the approximation is every document and each one is decoded and checked; that full scan
 * is the price A2 pays for updating tags without touching the vector.
 */
class TagDocValuesQuery(val tags: IntArray) : Query() {
    override fun createWeight(searcher: IndexSearcher, scoreMode: ScoreMode, boost: Float): Weight =
        object : ConstantScoreWeight(this, boost) {
            override fun scorerSupplier(context: LeafReaderContext): ScorerSupplier {
                val dv = DocValues.getBinary(context.reader(), VectorIndex.F_TAGDV)
                val twoPhase = object : TwoPhaseIterator(dv) {
                    override fun matches(): Boolean {
                        val b = dv.binaryValue()
                        val n = b.length / 4
                        for (t in tags) {
                            var lo = 0; var hi = n - 1; var found = false
                            while (lo <= hi) {
                                val mid = (lo + hi) ushr 1
                                val v = VectorIndex.decodeTagAt(b, mid)
                                if (v < t) lo = mid + 1 else if (v > t) hi = mid - 1 else { found = true; break }
                            }
                            if (!found) return false
                        }
                        return true
                    }

                    override fun matchCost(): Float = 40f
                }
                return DefaultScorerSupplier(ConstantScoreScorer(score(), scoreMode, twoPhase))
            }

            override fun isCacheable(ctx: LeafReaderContext) = false
        }

    override fun visit(visitor: QueryVisitor) = visitor.visitLeaf(this)
    override fun toString(field: String?) = "TagDV${tags.toList()}"
    override fun equals(other: Any?) = other is TagDocValuesQuery && other.tags.contentEquals(tags)
    override fun hashCode() = tags.contentHashCode()
}

object Filters {
    /** A1 filter: a conjunction of tag terms. */
    fun tagTerms(tags: IntArray): Query = when (tags.size) {
        0 -> MatchAllDocsQuery()
        1 -> TermQuery(Term(VectorIndex.F_TAG, tags[0].toString()))
        else -> BooleanQuery.Builder().apply {
            for (t in tags) add(TermQuery(Term(VectorIndex.F_TAG, t.toString())), BooleanClause.Occur.FILTER)
        }.build()
    }
}
