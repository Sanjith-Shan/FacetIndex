package facetindex.bench

import facetindex.data.F32Store
import facetindex.data.Formats
import facetindex.index.IndexSchema
import facetindex.index.LeafRows
import facetindex.index.VectorIndex
import facetindex.stream.Applier
import facetindex.stream.AttrMode
import facetindex.stream.DirectSink
import facetindex.stream.EventSink
import facetindex.stream.Events
import facetindex.stream.KafkaCatalogConsumer
import facetindex.stream.KafkaSink
import facetindex.stream.Topics
import facetindex.util.Args
import facetindex.util.JsonlWriter
import facetindex.util.Machine
import facetindex.util.deleteRecursively
import org.apache.lucene.search.KnnFloatVectorQuery
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * exp4: replays an official streaming runbook (inserts, deletes, searches) into a fresh Lucene HNSW
 * index, through Kafka or in process, and scores it the way the track does: recall@10 at every
 * search checkpoint against that step's published ground truth, averaged over all checkpoints.
 * Before each search, every produced event is applied and made visible (a blocking NRT refresh), as
 * the harness waits for each step to finish before the next.
 */
object RunbookBench {
    data class Step(val op: String, val start: Int, val end: Int)

    fun loadRunbook(path: Path, dataset: String): Pair<Int, List<Step>> {
        @Suppress("UNCHECKED_CAST")
        val all = Files.newBufferedReader(path).use { Yaml().load<Map<String, Any>>(it) }[dataset] as Map<Any, Any>
        val steps = ArrayList<Step>()
        var i = 1
        while (all.containsKey(i)) {
            @Suppress("UNCHECKED_CAST")
            val e = all[i] as Map<String, Any>
            steps += Step(e["operation"] as String, (e["start"] as? Int) ?: 0, (e["end"] as? Int) ?: 0)
            i++
        }
        return (all["max_pts"] as Int) to steps
    }

    fun run(a: Args): Int {
        val dir = a.path("data")
        val store = F32Store(Formats.readF32Matrix(dir.resolve(a.str("base", "msturing-10M-clustered.fbin"))))
        val queries = Formats.readF32Matrix(dir.resolve(a.str("query-file", "testQuery10K.fbin")))
        val dataset = a.str("dataset", "msturing-10M-clustered")
        val (maxPts, steps) = loadRunbook(a.path("runbook"), dataset)
        val gtDir = a.path("gt-dir")
        val via = a.str("via", "kafka")
        val threads = a.int("threads", 4)
        val ef = a.int("ef", 100)
        val k = 10
        val nq = minOf(a.int("queries", queries.n), queries.n)
        val path = a.path("index")
        deleteRecursively(path)
        val loadStart = Machine.load()
        val idx = VectorIndex(path, IndexSchema(tagTerms = false, tagDocValues = false, byteVectors = false, hnswM = a.int("m", 16), hnswBeam = a.int("beam", 100)),
            create = true, refreshMs = 0, ramBufferMb = a.double("ram-mb", 512.0), mergeWorkers = a.int("merge-workers", 2), deletesPctAllowed = a.double("deletes-pct", 20.0))
        val applier = Applier(idx, null, null, AttrMode.A3)
        val bootstrap = a.str("bootstrap", "127.0.0.1:19092")
        val topic = a.str("topic", "runbook-events")
        val consumer: KafkaCatalogConsumer?
        val sink: EventSink
        if (via == "kafka") {
            Topics.recreate(bootstrap, topic, a.int("partitions", 4))
            sink = KafkaSink(bootstrap, topic)
            consumer = KafkaCatalogConsumer(bootstrap, topic, "runbook-${System.nanoTime()}", applier, applyThreads = a.int("apply-threads", 4), maxPollRecords = 5000).start()
        } else {
            sink = DirectSink(applier); consumer = null
        }
        val insertPool = if (via != "kafka") Executors.newFixedThreadPool(a.int("apply-threads", 4)) else null
        val out = JsonlWriter(a.path("out"))
        val t0 = System.nanoTime()
        var seq = 0L
        val recalls = ArrayList<Double>()
        val searchPool = Executors.newFixedThreadPool(threads)
        var live = 0L
        var searchNanos = 0L
        var peakRss = 0L
        for ((si, s) in steps.withIndex()) {
            val stepNo = si + 1
            when (s.op) {
                "insert" -> {
                    if (insertPool != null) {
                        // In process: build the events and apply them with several threads.
                        val evs = (s.start until s.end).map { id -> Events.insert(++seq, id, null, store.m.row(id), IntArray(0)) }
                        applier.applyBatch(evs, a.int("apply-threads", 4), insertPool)
                    } else for (id in s.start until s.end) sink.send(Events.insert(++seq, id, null, store.m.row(id), IntArray(0)))
                    live += s.end - s.start
                }
                "delete" -> { for (id in s.start until s.end) sink.send(Events.delete(++seq, id)); live -= s.end - s.start }
                "search" -> {
                    sink.flush()
                    while (applier.applied.get() + applier.skipped.get() < seq) {
                        consumer?.error?.let { throw IllegalStateException("consumer failed", it) }
                        Thread.sleep(5)
                    }
                    idx.refreshNow()
                    val gt = Formats.readIbin(gtDir.resolve("step$stepNo.gt100"))
                    val ts = System.nanoTime()
                    val next = AtomicInteger()
                    val hits = java.util.concurrent.atomic.AtomicLong()
                    (0 until threads).map {
                        searchPool.submit {
                            while (true) {
                                val q = next.getAndIncrement(); if (q >= nq) break
                                val rows = idx.withSearcher { srch ->
                                    val top = srch.search(KnnFloatVectorQuery(VectorIndex.F_VEC, queries.row(q), maxOf(k, ef)), maxOf(k, ef)).scoreDocs
                                    val leaves = srch.indexReader.leaves()
                                    top.take(k).map { h -> val l = leaves[org.apache.lucene.index.ReaderUtil.subIndex(h.doc, leaves)]; LeafRows.of(l.reader()).rows[h.doc - l.docBase] }.toIntArray()
                                }
                                hits.addAndGet(gt.hits(q, rows, k).toLong())
                            }
                        }
                    }.forEach { it.get() }
                    val dt = System.nanoTime() - ts
                    searchNanos += dt
                    val recall = hits.get().toDouble() / (nq * k)
                    recalls += recall
                    val segs = idx.segmentCount()
                    val rss = Machine.rssBytes() ?: 0
                    peakRss = maxOf(peakRss, rss)
                    println("step $stepNo search: live=$live recall=${"%.4f".format(recall)} search_s=${"%.1f".format(dt / 1e9)} segments=$segs t=${"%.0f".format((System.nanoTime() - t0) / 1e9)}s")
                    out.write(linkedMapOf("experiment" to "exp4_checkpoint", "dataset" to dataset, "runbook" to a.req("runbook").substringAfterLast('/').substringAfterLast('\\'),
                        "step" to stepNo, "live" to live, "recall_at_10" to recall, "search_s" to dt / 1e9, "elapsed_s" to (System.nanoTime() - t0) / 1e9,
                        "segments" to segs, "rss_bytes" to rss, "via" to via, "ef" to ef))
                }
            }
        }
        val total = (System.nanoTime() - t0) / 1e9
        consumer?.close(); sink.close(); searchPool.shutdown(); insertPool?.shutdown()
        val row = linkedMapOf<String, Any?>(
            "experiment" to "exp4", "dataset" to dataset, "runbook" to a.req("runbook").substringAfterLast('/').substringAfterLast('\\'), "max_pts" to maxPts,
            "checkpoints" to recalls.size, "average_recall_at_10" to recalls.average(), "min_recall" to recalls.minOrNull(), "total_s" to total,
            "search_s" to searchNanos / 1e9, "via" to via, "ef" to ef, "hnsw_m" to a.int("m", 16), "hnsw_beam" to a.int("beam", 100), "queries_per_checkpoint" to nq,
            "peak_rss_sampled_bytes" to peakRss, "peak_working_set_bytes" to Machine.peakRssBytes(), "index_bytes" to idx.bytesOnDisk,
            "merge_s" to idx.merges.mergeNanos.get() / 1e9, "merges" to idx.merges.mergeCount.get(), "deletes_pct_allowed" to a.double("deletes-pct", 20.0),
            "applier" to applier.counters(), "threads" to threads,
            "note" to "scored as the track scores it: mean recall@10 over every search checkpoint, step GT from the organizers",
            "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(), "repeats" to 1,
        )
        out.write(row)
        out.close()
        println(row.filterKeys { it != "machine" })
        idx.close()
        return 0
    }
}
