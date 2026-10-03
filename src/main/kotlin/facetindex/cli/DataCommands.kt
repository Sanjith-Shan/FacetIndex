package facetindex.cli

import facetindex.attrs.AttributeStore
import facetindex.data.FilteredDataset
import facetindex.data.Formats
import facetindex.data.GroundTruth
import facetindex.data.QuerySet
import facetindex.strategy.Engine
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.Predicate
import facetindex.strategy.SearchBudget
import facetindex.util.Args
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import java.nio.file.Files
import java.util.SplittableRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Selectivity bins used throughout (fractions of live items). */
object Bins {
    val edges = doubleArrayOf(0.0, 1e-4, 1e-3, 1e-2, 1e-1, 1.0)
    val labels = listOf("<0.01%", "0.01-0.1%", "0.1-1%", "1-10%", ">10%")
    fun of(sel: Double): Int {
        for (i in 1 until edges.size) if (sel < edges[i]) return i - 1
        return edges.size - 2
    }
}

fun loadStore(ds: FilteredDataset): AttributeStore {
    val csr = ds.baseTags
    return AttributeStore(csr.ncol, csr.nrow).also { it.load(csr) }
}

/** Runs [body] for i in 0 until n on [threads] threads. */
fun parallel(n: Int, threads: Int, body: (Int) -> Unit) {
    val pool = Executors.newFixedThreadPool(threads)
    val next = AtomicInteger()
    val fs = (0 until threads).map {
        pool.submit {
            while (true) {
                val i = next.getAndIncrement()
                if (i >= n) break
                body(i)
            }
        }
    }
    fs.forEach { it.get() }
    pool.shutdown()
}

object DataCommands {
    /**
     * m0 stats: tag frequency distribution, one-tag vs two-tag share, per-query selectivity
     * histogram (exact, from bitmap cardinalities), empty-result counts.
     */
    fun stats(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc-10M"))
        val t0 = System.nanoTime()
        val csr = ds.baseTags
        val counts = csr.columnCounts()
        val store = AttributeStore(csr.ncol, csr.nrow).also { it.load(csr) }
        val loadS = (System.nanoTime() - t0) / 1e9
        val sorted = counts.filter { it > 0 }.sortedDescending()
        val perItem = IntArray(csr.nrow) { csr.rowLength(it) }.sorted()
        val freqBuckets = listOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)
        val tagHist = LinkedHashMap<String, Int>()
        var lo = 0
        for (hi in freqBuckets) { tagHist["[$lo,$hi)"] = counts.count { it in lo until hi && it > 0 }; lo = hi }
        val out = JsonlWriter(a.path("out"))
        val base = linkedMapOf<String, Any?>(
            "experiment" to "m0_data", "dataset" to ds.name, "kind" to "base",
            "items" to csr.nrow, "vocabulary" to csr.ncol, "tag_entries" to csr.nnz.toLong(),
            "tags_per_item_mean" to csr.nnz.toDouble() / csr.nrow,
            "tags_per_item_p50" to perItem[perItem.size / 2], "tags_per_item_p99" to perItem[(perItem.size * 0.99).toInt()], "tags_per_item_max" to perItem.last(),
            "items_with_no_tags" to perItem.count { it == 0 },
            "tags_used" to sorted.size, "top_tag_counts" to sorted.take(20),
            "tag_frequency_histogram" to tagHist,
            "attr_store_bytes" to store.bytes(), "load_s" to loadS,
            "machine" to Machine.info, "load" to Machine.load(), "repeats" to 1,
        )
        out.write(base); println(base.filterKeys { it != "machine" })
        for (which in a.list("queries", "public,private")) {
            val qs = ds.queries(which)
            val n = qs.n
            val sel = DoubleArray(n)
            val matches = LongArray(n)
            val nt = IntArray(n)
            for (i in 0 until n) {
                val tags = qs.tagsOf(i)
                nt[i] = tags.size
                matches[i] = store.matchCount(tags).toLong()
                sel[i] = matches[i].toDouble() / csr.nrow
            }
            val hist = IntArray(Bins.labels.size)
            val hist1 = IntArray(Bins.labels.size)
            val hist2 = IntArray(Bins.labels.size)
            for (i in 0 until n) { val b = Bins.of(sel[i]); hist[b]++; if (nt[i] == 1) hist1[b]++ else hist2[b]++ }
            val ss = sel.sortedArray()
            val gt = qs.gt
            val row = linkedMapOf<String, Any?>(
                "experiment" to "m0_data", "dataset" to ds.name, "kind" to "queries", "query_set" to which,
                "queries" to n, "one_tag" to nt.count { it == 1 }, "two_tag" to nt.count { it == 2 }, "more_tags" to nt.count { it > 2 }, "no_tags" to nt.count { it == 0 },
                "empty_result" to matches.count { it == 0L }, "fewer_than_10_matches" to matches.count { it < 10 },
                "selectivity_bins" to Bins.labels, "selectivity_hist" to hist.toList(), "selectivity_hist_one_tag" to hist1.toList(), "selectivity_hist_two_tag" to hist2.toList(),
                "selectivity_p01" to ss[(n * 0.01).toInt()], "selectivity_p50" to ss[n / 2], "selectivity_p99" to ss[(n * 0.99).toInt()],
                "matches_mean" to matches.average(), "matches_p50" to matches.sorted()[n / 2],
                "gt_k" to gt?.k, "gt_queries_with_padding" to gt?.let { g -> (0 until g.nq).count { g.nValid(it) < g.k } },
                "machine" to Machine.info, "repeats" to 1,
            )
            out.write(row); println(row.filterKeys { it != "machine" })
        }
        out.close()
        return 0
    }

    /**
     * A seeded random slice of the base set with every query kept and filtered ground truth
     * recomputed exactly over the slice. Queries with no match in the slice keep a GT row of -1s and
     * are counted (and dropped by the evaluators).
     */
    fun slice(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), "yfcc-10M")
        val out = a.path("out")
        val n = a.int("n", 1_000_000)
        val seed = a.long("seed", 20261003L)
        Files.createDirectories(out)
        val rnd = SplittableRandom(seed)
        val total = ds.base.size
        val chosen = java.util.BitSet(total)
        var c = 0
        while (c < n) { val r = rnd.nextInt(total); if (!chosen.get(r)) { chosen.set(r); c++ } }
        val rows = IntArray(n).also { var i = 0; var r = chosen.nextSetBit(0); while (r >= 0) { it[i++] = r; r = chosen.nextSetBit(r + 1) } }
        Formats.writeU8bin(out.resolve("base.u8bin"), n, ds.base.dim) { ds.base.m.row(rows[it]) }
        Formats.writeSpmat(out.resolve("base.metadata.spmat"), ds.baseTags.selectRows(rows))
        Files.write(out.resolve("slice_rows.txt"), listOf("# seed=$seed n=$n source=${ds.dir}") + rows.map { it.toString() })
        for (which in listOf("public", "private")) {
            val (q, m) = when (which) {
                "public" -> "query.public.100K.u8bin" to "query.metadata.public.100K.spmat"
                else -> "query.private.${FilteredDataset.PRIVATE_KEY}.100K.u8bin" to "query.metadata.private.${FilteredDataset.PRIVATE_KEY}.100K.spmat"
            }
            Files.copy(ds.dir.resolve(q), out.resolve(q), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            Files.copy(ds.dir.resolve(m), out.resolve(m), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        println("slice: wrote $n rows to $out")
        val sliceDs = FilteredDataset(out, "yfcc-1M-slice")
        for (which in listOf("public", "private")) {
            val gtName = if (which == "public") "GT.public.ibin" else "GT.private.${FilteredDataset.PRIVATE_KEY}.ibin"
            val gt = exactGt(sliceDs, sliceDs.queries(which), a.int("threads", 4), 10)
            Formats.writeIbin(out.resolve(gtName), gt)
            println("slice: $which GT written, empty=${(0 until gt.nq).count { gt.nValid(it) == 0 }}")
        }
        return 0
    }

    /** Exact filtered top-k for every query with S0 (brute force over the matching rows). */
    fun exactGt(ds: FilteredDataset, qs: QuerySet, threads: Int, k: Int, limit: Int = Int.MAX_VALUE): GroundTruth {
        val store = loadStore(ds)
        val e = Engine(ds.base, store, null)
        val s0 = PreFilterBruteForce(e)
        val nq = minOf(qs.n, limit)
        val ids = IntArray(nq * k) { -1 }
        val dists = FloatArray(nq * k) { Float.MAX_VALUE }
        parallel(nq, threads) { i ->
            val r = s0.search(qs.vector(i), Predicate(qs.tagsOf(i)), k, SearchBudget())
            for (j in r.rows.indices) { ids[i * k + j] = r.rows[j]; dists[i * k + j] = r.dists[j] }
        }
        return GroundTruth(nq, k, ids, dists)
    }

    /**
     * Checks a dataset's ground truth against our own S0 on [limit] queries: the fraction of GT
     * neighbours S0 finds (ties counted the benchmark's way) and the max distance disagreement.
     */
    fun verifyGt(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc-10M"))
        val out = JsonlWriter(a.path("out"))
        for (which in a.list("queries", "public,private")) {
            val qs = ds.queries(which)
            val gt = qs.gt ?: error("no GT for $which")
            val limit = minOf(a.int("limit", 2000), qs.n)
            val step = qs.n / limit
            val picks = IntArray(limit) { it * step }
            val sub = QuerySetView(qs, picks)
            val mine = exactGt(ds, sub.asQuerySet(), a.int("threads", 4), gt.k)
            var hits = 0L; var possible = 0L; var exactRows = 0
            var maxDistDiff = 0f
            for (j in picks.indices) {
                val q = picks[j]
                val res = mine.row(j).filter { it >= 0 }.toIntArray()
                val h = gt.hits(q, res, minOf(10, gt.k))
                hits += h; possible += minOf(10, gt.k)
                val g = gt.row(q).filter { it >= 0 }
                if (g.toSet() == res.toSet()) exactRows++
                val gd = gt.distRow(q); val md = mine.distRow(j)
                for (t in 0 until minOf(gt.nValid(q), mine.nValid(j))) maxDistDiff = maxOf(maxDistDiff, Math.abs(gd[t] - md[t]))
            }
            val row = linkedMapOf<String, Any?>(
                "experiment" to "m0_gt_verify", "dataset" to ds.name, "query_set" to which, "queries_checked" to limit,
                "query_stride" to step, "recall_at_10_of_s0_vs_gt" to hits.toDouble() / possible,
                "queries_with_identical_id_sets" to exactRows, "max_abs_distance_diff" to maxDistDiff,
                "method" to "S0 (Kotlin, SIMD kernel) against the published GT file",
                "machine" to Machine.info, "repeats" to 1,
            )
            out.write(row); println(row.filterKeys { it != "machine" })
        }
        out.close()
        return 0
    }
}

/** A subset of a query set, in a given order. */
class QuerySetView(private val qs: QuerySet, private val picks: IntArray) {
    fun asQuerySet(): QuerySet {
        val d = qs.vectors.d
        val seg = java.lang.foreign.Arena.global().allocate(picks.size.toLong() * d)
        for (j in picks.indices) java.lang.foreign.MemorySegment.copy(qs.vectors.seg, picks[j].toLong() * d, seg, j.toLong() * d, d.toLong())
        val tags = qs.tags.selectRows(picks)
        return QuerySet(qs.name, facetindex.data.U8Matrix(picks.size, d, seg), tags, null)
    }
}
