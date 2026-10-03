package facetindex.cli

import facetindex.data.FilteredDataset
import facetindex.index.IndexItem
import facetindex.index.IndexSchema
import facetindex.index.VectorIndex
import facetindex.ivf.IvfIndex
import facetindex.ivf.KMeans
import facetindex.util.Args
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import facetindex.util.copyRecursively
import facetindex.util.deleteRecursively
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object BuildCommands {
    /** k-means on a sample, then assign every base row; saves centroids and assignment. */
    fun buildIvf(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc"))
        val k = a.int("clusters", 4096)
        val sample = a.int("sample", maxOf(64 * k, 500_000))
        val iters = a.int("iters", 10)
        val seed = a.long("seed", 7L)
        val rows = a.int("rows", ds.base.size)
        val out = a.path("out")
        val loadStart = Machine.load()
        val km = KMeans(ds.base.dim, a.int("threads", 4))
        val t0 = System.nanoTime()
        val res = km.train(ds.base, k, minOf(sample, rows), iters, seed) { println(it) }
        val t1 = System.nanoTime()
        val assign = km.assignAll(ds.base, 0 until rows, res.centroids, k) { println(it) }
        val t2 = System.nanoTime()
        km.close()
        val ivf = IvfIndex(k, ds.base.dim, res.centroids, assign)
        ivf.save(out)
        val sizes = (0 until k).map { ivf.size(it) }.sorted()
        val row = linkedMapOf<String, Any?>(
            "experiment" to "build_ivf", "dataset" to ds.name, "clusters" to k, "rows" to rows, "sample" to res.sampleSize,
            "iterations" to iters, "seed" to seed, "train_s" to (t1 - t0) / 1e9, "assign_s" to (t2 - t1) / 1e9,
            "inertia_per_point_by_iter" to res.inertiaPerIter.map { it / res.sampleSize },
            "cluster_size_min" to sizes.first(), "cluster_size_p50" to sizes[k / 2], "cluster_size_max" to sizes.last(),
            "empty_clusters" to sizes.count { it == 0 }, "file" to out.toString(), "ivf_bytes" to ivf.bytes(),
            "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(), "repeats" to 1,
        )
        JsonlWriter(a.path("results")).use { it.write(row) }
        println(row.filterKeys { it != "machine" })
        return 0
    }

    /**
     * Builds (or appends to) the Lucene index over rows [from, to) with several indexing threads, then
     * force-merges to one segment. With --copy-to the finished index is copied (the 9M snapshot that
     * the filtered streaming workload starts from).
     */
    fun buildIndex(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), a.str("name", "yfcc"))
        val path = a.path("index")
        val append = a.flag("append")
        val from = a.int("from", 0)
        val to = a.int("to", ds.base.size)
        val threads = a.int("threads", 4)
        val ivfs = a.list("ivf", "").map { IvfIndex.load(Path.of(it)) }
        val schema = IndexSchema(
            tagTerms = a.flag("tag-terms", true), tagDocValues = a.flag("tag-dv", true),
            clusterFields = ivfs.map { VectorIndex.clusterField(it.k) },
            hnswM = a.int("m", 16), hnswBeam = a.int("beam", 100),
        )
        if (!append) deleteRecursively(path)
        val loadStart = Machine.load()
        val csr = ds.baseTags
        val idx = VectorIndex(path, schema, create = !append, refreshMs = 0, ramBufferMb = a.double("ram-mb", 1024.0), mergeWorkers = a.int("merge-workers", threads))
        val t0 = System.nanoTime()
        val next = AtomicInteger(from)
        val pool = Executors.newFixedThreadPool(threads)
        val batch = 1000
        val fs = (0 until threads).map {
            pool.submit {
                while (true) {
                    val lo = next.getAndAdd(batch)
                    if (lo >= to) break
                    for (r in lo until minOf(to, lo + batch)) {
                        idx.add(IndexItem(
                            row = r, byteVec = ds.base.luceneBytes(r), tags = csr.row(r),
                            clusters = ivfs.associate { VectorIndex.clusterField(it.k) to it.clusterOf(r) },
                        ))
                    }
                    if ((lo / batch) % 500 == 0) println("index: $lo / $to t=${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
                }
            }
        }
        fs.forEach { it.get() }
        pool.shutdown()
        idx.commit()
        val t1 = System.nanoTime()
        val segsBefore = idx.segmentCount()
        println("index: added ${to - from} docs in ${"%.0f".format((t1 - t0) / 1e9)}s, $segsBefore segments; force-merging")
        if (a.flag("force-merge", true)) { idx.forceMerge(1); idx.commit() }
        val t2 = System.nanoTime()
        idx.refreshNow()
        val segs = idx.segmentCount()
        val maxDoc = idx.withSearcher { it.indexReader.maxDoc() }
        val bytes = idx.bytesOnDisk
        idx.close()
        a.pathOrNull("copy-to")?.let { dst ->
            deleteRecursively(dst); Files.createDirectories(dst); copyRecursively(path, dst)
            println("index: copied to $dst")
        }
        val row = linkedMapOf<String, Any?>(
            "experiment" to "build_index", "dataset" to ds.name, "index" to path.toString(), "append" to append,
            "rows_from" to from, "rows_to" to to, "max_doc" to maxDoc, "segments_before_merge" to segsBefore, "segments" to segs,
            "add_s" to (t1 - t0) / 1e9, "force_merge_s" to (t2 - t1) / 1e9, "bytes_on_disk" to bytes,
            "hnsw_m" to schema.hnswM, "hnsw_beam" to schema.hnswBeam, "index_threads" to threads, "merge_workers" to a.int("merge-workers", threads),
            "cluster_fields" to schema.clusterFields, "tag_terms" to schema.tagTerms, "tag_dv" to schema.tagDocValues,
            "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(), "repeats" to 1,
        )
        JsonlWriter(a.path("results")).use { it.write(row) }
        println(row.filterKeys { it != "machine" })
        return 0
    }
}
