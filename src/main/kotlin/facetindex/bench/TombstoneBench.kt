package facetindex.bench

import facetindex.cli.loadStore
import facetindex.data.FilteredDataset
import facetindex.index.IndexSchema
import facetindex.index.VectorIndex
import facetindex.strategy.Engine
import facetindex.strategy.LuceneFilteredHnsw
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import facetindex.strategy.SearcherSource
import facetindex.util.Args
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import facetindex.util.copyRecursively
import facetindex.util.deleteRecursively
import org.apache.lucene.index.NoMergePolicy
import org.apache.lucene.search.IndexSearcher
import java.nio.file.Files
import java.util.SplittableRandom

/**
 * Deleted documents stay in Lucene's HNSW graph as tombstones until a merge rewrites the segment:
 * they are still traversed but never returned. This measures filtered recall@10 and latency of S2
 * and S3 as the deleted fraction grows (merges disabled), then again after forceMergeDeletes.
 */
object TombstoneBench {
    fun run(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc-1M-slice"))
        val work = a.path("work-index")
        deleteRecursively(work); Files.createDirectories(work); copyRecursively(a.path("index"), work)
        val attrs = loadStore(ds)
        val idx = VectorIndex(work, IndexSchema(clusterFields = a.list("cluster-fields", "")), create = false, refreshMs = 0)
        idx.writer.config.mergePolicy = NoMergePolicy.INSTANCE
        idx.refreshNow()
        val e = Engine(ds.base, attrs, object : SearcherSource {
            override fun <T> withSearcher(body: (IndexSearcher) -> T): T = idx.withSearcher(body)
        })
        val s0 = PreFilterBruteForce(e)
        val strategies = mapOf("S2" to LuceneFilteredHnsw(e, "S2", 0), "S3" to LuceneFilteredHnsw(e, "S3", 60))
        val budget = SearchBudget(ef = a.int("ef", 64))
        val qs = ds.queries(a.str("queries", "private"))
        val nq = minOf(a.int("limit", 2000), qs.n)
        val out = JsonlWriter(a.path("out"))
        val rnd = SplittableRandom(3)
        val n = ds.base.size
        var deleted = 0
        fun measure(fraction: Double, phase: String) {
            idx.refreshNow()
            for ((name, s) in strategies) {
                var hits = 0L; var poss = 0L; var latNs = 0L
                for (i in 0 until nq) {
                    val p = Predicate(qs.tagsOf(i))
                    val truth = s0.search(qs.vector(i), p, 10, SearchBudget()).rows.toSet()
                    if (truth.isEmpty()) continue
                    val t0 = System.nanoTime()
                    val r = s.search(qs.vector(i), p, 10, budget)
                    latNs += System.nanoTime() - t0
                    hits += r.rows.count { it in truth }; poss += truth.size
                }
                val row = linkedMapOf<String, Any?>("experiment" to "tombstones", "dataset" to ds.name, "deleted_fraction" to fraction, "phase" to phase,
                    "strategy" to name, "ef" to budget.ef, "recall_at_10" to hits.toDouble() / poss, "mean_latency_us" to latNs / 1000.0 / nq,
                    "segments" to idx.segmentCount(), "max_doc" to idx.withSearcher { it.indexReader.maxDoc() }, "num_docs" to idx.withSearcher { it.indexReader.numDocs() },
                    "queries" to nq, "threads" to 1, "machine" to Machine.info, "load" to Machine.load(300), "repeats" to 1)
                out.write(row)
                println("deleted=$fraction $phase $name recall=${row["recall_at_10"]} lat=${row["mean_latency_us"]}us")
            }
        }
        for (f in a.doubles("fractions", "0,0.1,0.2,0.4")) {
            val target = (f * n).toInt()
            while (deleted < target) {
                val r = rnd.nextInt(n)
                if (attrs.delete(r)) { idx.delete(r); deleted++ }
            }
            idx.commit()
            measure(f, "tombstones")
        }
        idx.writer.config.mergePolicy = org.apache.lucene.index.TieredMergePolicy()
        val t0 = System.nanoTime()
        idx.writer.forceMergeDeletes(true); idx.commit()
        val mergeS = (System.nanoTime() - t0) / 1e9
        println("forceMergeDeletes ${"%.1f".format(mergeS)}s")
        measure(deleted.toDouble() / n, "after_force_merge_deletes(${"%.0f".format(mergeS)}s)")
        out.close()
        idx.closeAndDelete()
        return 0
    }
}
