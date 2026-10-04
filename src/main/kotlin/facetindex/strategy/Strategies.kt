package facetindex.strategy

import facetindex.attrs.AttributeStore
import facetindex.data.QueryVector
import facetindex.data.U8Store
import facetindex.data.VectorStore
import facetindex.index.Filters
import facetindex.index.LeafRows
import facetindex.index.RowSetQuery
import facetindex.index.TagDocValuesQuery
import facetindex.index.VectorIndex
import facetindex.ivf.IvfIndex
import org.apache.lucene.document.IntField
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnByteVectorQuery
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.knn.KnnSearchStrategy
import org.roaringbitmap.FastAggregation
import org.roaringbitmap.RoaringBitmap

/** Where strategies get an IndexSearcher: a live [VectorIndex] or a static reader. */
interface SearcherSource {
    fun <T> withSearcher(body: (IndexSearcher) -> T): T
}

/** How S2 and S3 express the predicate to Lucene. */
enum class FilterMode { TERMS, DOCVALUES, EXTERNAL }

/** Everything a strategy may read. */
class Engine(
    val store: VectorStore,
    val attrs: AttributeStore,
    val searchers: SearcherSource?,
    val ivfs: Map<Int, IvfIndex> = emptyMap(),
    val filterMode: FilterMode = FilterMode.EXTERNAL,
    val ranges: facetindex.attrs.RangeStore? = null,
) {
    /** Strategies added by later milestones (S5, S6), built from their spec parameters. */
    val extraStrategies = HashMap<String, (Map<String, String>) -> FilterStrategy>()

    /**
     * Planner statistics. Tags alone: exact. With range clauses: each clause's count is exact (two
     * binary searches), combined with the tag count under an independence assumption, so the result
     * is an estimate ([PredicateStats.exact] false); exp7 measures its error.
     */
    fun stats(p: Predicate): PredicateStats {
        val live = attrs.liveCount().toLong()
        if (p.ranges.isEmpty()) return PredicateStats(attrs.matchCount(p.tags).toLong(), live, p.tags.size, exact = true)
        val rs = ranges ?: error("range predicate but no range store")
        var est = if (p.tags.isEmpty()) live.toDouble() else attrs.matchCount(p.tags).toDouble()
        for (c in p.ranges) est *= rs.count(c).toDouble() / rs.n
        return PredicateStats(Math.round(est), live, maxOf(1, p.tags.size), exact = false)
    }

    /** Exact count of rows passing tags and ranges (evaluation only; costs a pass over the smaller side). */
    fun exactMatches(p: Predicate): Long = if (p.ranges.isEmpty()) attrs.matchCount(p.tags).toLong() else matching(p).longCardinality

    /** Exact rows passing tags and ranges: walks the smaller of the tag set and the narrowest range slice. */
    fun matching(p: Predicate): RoaringBitmap {
        if (p.ranges.isEmpty()) return attrs.matching(p.tags)
        val rs = ranges!!
        if (p.tags.isEmpty()) return rs.matching(p.ranges)
        val tagCount = attrs.matchCount(p.tags)
        val rangeCount = p.ranges.minOf { rs.count(it) }
        return if (tagCount <= rangeCount) {
            val out = RoaringBitmap()
            attrs.matching(p.tags).forEach { r: Int -> if (rs.pass(r, p.ranges)) out.add(r) }
            out
        } else {
            val out = RoaringBitmap()
            rs.matching(p.ranges).forEach { r: Int -> if (attrs.hasAll(r, p.tags)) out.add(r) }
            out
        }
    }

    fun passes(row: Int, p: Predicate): Boolean = attrs.hasAll(row, p.tags) && (p.ranges.isEmpty() || ranges!!.pass(row, p.ranges))

    fun luceneFilter(p: Predicate, mode: FilterMode = filterMode): Query? = when {
        p.tags.isEmpty() && p.ranges.isEmpty() -> null
        mode == FilterMode.EXTERNAL -> RowSetQuery(matching(p), "predicate")
        else -> {
            // Lucene-side filters: tag terms or the tag doc value, plus each range as a LongField points query.
            val tagQ = if (p.tags.isEmpty()) null else if (mode == FilterMode.TERMS) Filters.tagTerms(p.tags) else TagDocValuesQuery(p.tags)
            if (p.ranges.isEmpty()) tagQ else org.apache.lucene.search.BooleanQuery.Builder().apply {
                tagQ?.let { add(it, org.apache.lucene.search.BooleanClause.Occur.FILTER) }
                for (c in p.ranges) add(org.apache.lucene.document.LongField.newRangeQuery(c.attr, c.lo, c.hi), org.apache.lucene.search.BooleanClause.Occur.FILTER)
            }.build()
        }
    }

    fun luceneQueryVector(q: QueryVector): Any = when (q) {
        is QueryVector.U8 -> U8Store.toLuceneBytes(q.v.copyOf())
        is QueryVector.F32 -> q.v
    }
}

/** Builds Lucene's kNN query for either vector encoding. */
internal fun knnQuery(e: Engine, q: QueryVector, k: Int, filter: Query?, strategy: KnnSearchStrategy): Query =
    when (val v = e.luceneQueryVector(q)) {
        is ByteArray -> KnnByteVectorQuery(VectorIndex.F_VEC, v, k, filter, strategy)
        is FloatArray -> KnnFloatVectorQuery(VectorIndex.F_VEC, v, k, filter, strategy)
        else -> error("unreachable")
    }

/** Runs a kNN query and maps hits to rows, re-scoring the kept rows exactly against the store. */
internal fun runKnn(e: Engine, query: Query, fetch: Int, k: Int, accept: ((Int) -> Boolean)?, q: QueryVector): SearchResult =
    e.searchers!!.withSearcher { s ->
        val hits = s.search(query, fetch).scoreDocs
        val leaves = s.indexReader.leaves()
        val top = TopK(k)
        for (h in hits) {
            val leaf = leaves[org.apache.lucene.index.ReaderUtil.subIndex(h.doc, leaves)]
            val row = LeafRows.of(leaf.reader()).rows[h.doc - leaf.docBase]
            if (accept == null || accept(row)) top.offer(e.store.dist(q, row), row)
        }
        top.result(scored = hits.size.toLong())
    }

/**
 * S0, pre-filter brute force: iterate the predicate's rows (tag bitmap, or the intersection of two)
 * and score every one with the SIMD kernel, keeping the top k. Exact.
 */
class PreFilterBruteForce(private val e: Engine) : FilterStrategy {
    override val name = "S0"

    override fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult {
        val top = TopK(k)
        var scored = 0L
        val u8 = e.store as? U8Store
        val qb = (q as? QueryVector.U8)?.v?.let { v -> u8?.prepare(v) }
        fun score(r: Int) {
            val d = if (u8 != null && qb != null) u8.distInt(qb, r).toFloat() else e.store.dist(q, r)
            top.offer(d, r); scored++
        }
        if (p.ranges.isNotEmpty() || p.tags.isEmpty()) {
            e.matching(p).forEach { r: Int -> score(r) }
        } else {
            e.attrs.withPostings(p.tags) { bms ->
                if (bms.any { it == null }) return@withPostings
                val it = if (bms.size == 1) bms[0]!! else RoaringBitmap.and(bms[0]!!, bms[1]!!)
                it.forEach { r: Int -> score(r) }
            }
        }
        return top.result(scored)
    }
}

/**
 * S1, post-filter HNSW: unfiltered graph search for k' = k / selectivity x safety (capped), then drop
 * rows that fail the predicate. Recall collapses as selectivity falls.
 */
class PostFilterHnsw(private val e: Engine) : FilterStrategy {
    override val name = "S1"

    override fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult {
        val sel = e.stats(p).selectivity
        if (sel <= 0.0) return SearchResult.EMPTY
        val want = Math.ceil(k / sel * budget.safety).toLong()
        val fetch = want.coerceIn(maxOf(k, budget.ef).toLong(), budget.maxFetch.toLong()).toInt()
        val query = knnQuery(e, q, fetch, null, KnnSearchStrategy.Hnsw.DEFAULT)
        return runKnn(e, query, fetch, k, { e.passes(it, p) }, q)
    }
}

/**
 * S2 (threshold 0, Lucene 10.5's default) and S3 (threshold > 0, Lucene's ACORN-1-style filtered
 * searcher from PR #14160): Lucene's filtered HNSW with its own exact-search fallback. Both are
 * Lucene's code; this class only builds the query.
 */
class LuceneFilteredHnsw(private val e: Engine, override val name: String, private val defaultThreshold: Int, private val mode: FilterMode? = null) : FilterStrategy {
    override fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult {
        val fetch = maxOf(k, budget.ef)
        val threshold = if (budget.threshold > 0) budget.threshold else defaultThreshold
        val filter = e.luceneFilter(p, mode ?: e.filterMode)
        val query = knnQuery(e, q, fetch, filter, KnnSearchStrategy.Hnsw(threshold))
        return runKnn(e, query, fetch, k, null, q)
    }
}

/**
 * S4, IVF with posting-list intersection: probe the nprobe nearest centroids, intersect each probed
 * cluster's bitmap with the tag bitmaps, score survivors exactly. When the predicate's own match set
 * is smaller than the probed clusters, it walks the match set instead and checks each row's cluster.
 */
class IvfIntersect(private val e: Engine, private val clusters: Int) : FilterStrategy {
    override val name = "S4"
    private val ivf get() = e.ivfs[clusters] ?: error("no IVF with $clusters clusters loaded")

    override fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult {
        val ivf = ivf
        val probes = ivf.probe(q, maxOf(1, budget.nprobe))
        val top = TopK(k)
        var scored = 0L
        val u8 = e.store as? U8Store
        val qb = (q as? QueryVector.U8)?.v?.let { v -> u8?.prepare(v) }
        val rs = if (p.ranges.isEmpty()) null else e.ranges!!
        fun score(r: Int) {
            if (rs != null && !rs.pass(r, p.ranges)) return
            val d = if (u8 != null && qb != null) u8.distInt(qb, r).toFloat() else e.store.dist(q, r)
            top.offer(d, r); scored++
        }
        if (p.tags.isEmpty()) {
            if (rs != null) {
                // Range only: walk the range's rows if they are fewer than the probed clusters.
                val probed = java.util.BitSet(ivf.k).apply { probes.forEach { set(it) } }
                val rangeCount = p.ranges.minOf { rs.count(it) }
                if (rangeCount <= probes.sumOf { ivf.size(it).toLong() }) {
                    rs.matching(p.ranges).forEach { r: Int -> if (probed.get(ivf.clusterOf(r))) score(r) }
                    return top.result(scored)
                }
            }
            for (c in probes) ivf.withList(c) { it.forEach { r: Int -> score(r) } }
            return top.result(scored)
        }
        e.attrs.withPostings(p.tags) { bms ->
            if (bms.any { it == null }) return@withPostings
            val probedSize = probes.sumOf { ivf.size(it).toLong() }
            val smallest = bms.minOf { it!!.cardinality.toLong() }
            if (smallest <= probedSize) {
                // Walk the predicate's rows; keep those whose cluster was probed.
                val probed = java.util.BitSet(ivf.k).apply { probes.forEach { set(it) } }
                val match = if (bms.size == 1) bms[0]!! else RoaringBitmap.and(bms[0]!!, bms[1]!!)
                match.forEach { r: Int -> if (probed.get(ivf.clusterOf(r))) score(r) }
            } else {
                for (c in probes) {
                    val survivors = ivf.withList(c) { list ->
                        if (bms.size == 1) RoaringBitmap.and(list, bms[0]!!) else FastAggregation.and(list, bms[0]!!, bms[1]!!)
                    }
                    survivors.forEach { r: Int -> score(r) }
                }
            }
        }
        return top.result(scored)
    }
}

/**
 * S4-L: the same IVF plan run as a pure Lucene filter. The probed cluster ids become an
 * IntField set query, conjoined with the tag terms; the matching documents are scored exactly with
 * Lucene's own vector scorer.
 */
class IvfAsLuceneFilter(private val e: Engine, private val clusters: Int) : FilterStrategy {
    override val name = "S4L"
    private val field = VectorIndex.clusterField(clusters)

    override fun search(q: QueryVector, p: Predicate, k: Int, budget: SearchBudget): SearchResult {
        val ivf = e.ivfs[clusters] ?: error("no IVF with $clusters clusters loaded")
        val probes = ivf.probe(q, maxOf(1, budget.nprobe))
        val filter = BooleanQuery.Builder().apply {
            add(IntField.newSetQuery(field, *probes), BooleanClause.Occur.FILTER)
            for (t in p.tags) add(org.apache.lucene.search.TermQuery(org.apache.lucene.index.Term(VectorIndex.F_TAG, t.toString())), BooleanClause.Occur.FILTER)
            for (c in p.ranges) add(org.apache.lucene.document.LongField.newRangeQuery(c.attr, c.lo, c.hi), BooleanClause.Occur.FILTER)
        }.build()
        val lq = e.luceneQueryVector(q)
        return e.searchers!!.withSearcher { s ->
            val weight = s.createWeight(s.rewrite(filter), ScoreMode.COMPLETE_NO_SCORES, 1f)
            val top = TopK(k)
            var scored = 0L
            for (leaf in s.indexReader.leaves()) {
                val scorer = weight.scorer(leaf) ?: continue
                val rows = LeafRows.of(leaf.reader())
                val live = leaf.reader().liveDocs
                val vs = when (lq) {
                    is ByteArray -> leaf.reader().getByteVectorValues(VectorIndex.F_VEC)?.scorer(lq)
                    is FloatArray -> leaf.reader().getFloatVectorValues(VectorIndex.F_VEC)?.scorer(lq)
                    else -> null
                } ?: continue
                val vit = vs.iterator()
                val it = scorer.iterator()
                var doc = it.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if ((live == null || live.get(doc)) && vit.advance(doc) == doc) {
                        // EUCLIDEAN score is 1 / (1 + d^2); invert it.
                        val sc = vs.score()
                        top.offer(1f / sc - 1f, rows.rows[doc]); scored++
                    }
                    doc = it.nextDoc()
                }
            }
            val r = top.result(scored)
            // Report exact distances from the store for the kept rows.
            SearchResult(r.rows, FloatArray(r.rows.size) { e.store.dist(q, r.rows[it]) }, scored)
        }
    }
}
