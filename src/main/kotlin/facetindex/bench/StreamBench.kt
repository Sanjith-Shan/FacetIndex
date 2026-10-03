package facetindex.bench

import facetindex.attrs.AttributeStore
import facetindex.data.Csr
import facetindex.data.FilteredDataset
import facetindex.index.IndexSchema
import facetindex.index.VectorIndex
import facetindex.ivf.IvfIndex
import facetindex.stream.Applier
import facetindex.stream.AttrMode
import facetindex.stream.DirectSink
import facetindex.stream.EventSink
import facetindex.stream.Events
import facetindex.stream.KafkaCatalogConsumer
import facetindex.stream.KafkaSink
import facetindex.stream.Topics
import facetindex.strategy.Engine
import facetindex.strategy.FilterMode
import facetindex.strategy.IvfIntersect
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
import org.HdrHistogram.ConcurrentHistogram
import org.apache.lucene.search.IndexSearcher
import java.nio.file.Files
import java.nio.file.Path
import java.util.SplittableRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * The constructed filtered streaming workload on YFCC (not a competition track): the index starts
 * from the first [base] items, the remaining items arrive as inserts, an equal rate of deletes removes
 * random live items, and SetAttrs events add a frequent tag to, or remove one of the original tags
 * from, random live items. Three open-loop event streams at fixed rates, merged by due time.
 */
class YfccWorkload(
    private val ds: FilteredDataset,
    private val csr: Csr,
    private val base: Int,
    private val insertRate: Double,
    private val deleteRate: Double,
    private val attrRate: Double,
    seed: Long,
) {
    private val rnd = SplittableRandom(seed)
    private val deleted = java.util.BitSet(base)
    private val topTags: IntArray = csr.columnCounts().withIndex().sortedByDescending { it.value }.take(1000).map { it.index }.toIntArray()
    private var nextInsert = base
    var seq = 0L
        private set
    val produced = mapOf("insert" to AtomicLong(), "delete" to AtomicLong(), "set_attrs" to AtomicLong())

    @Volatile
    var running = true

    private fun liveOld(): Int {
        while (true) { val r = rnd.nextInt(base); if (!deleted.get(r)) return r }
    }

    /** Produces events into [sink] for [seconds] (or until stopped). */
    fun run(sink: EventSink, seconds: Double) {
        val start = System.nanoTime()
        val end = start + (seconds * 1e9).toLong()
        val rates = doubleArrayOf(insertRate, deleteRate, attrRate)
        val count = LongArray(3)
        while (running) {
            // Next due event among the three streams.
            var which = -1; var due = Long.MAX_VALUE
            for (t in 0..2) if (rates[t] > 0) {
                val d = start + (count[t] * 1e9 / rates[t]).toLong()
                if (d < due) { due = d; which = t }
            }
            if (which < 0 || due >= end) break
            while (true) { val w = due - System.nanoTime(); if (w <= 0) break; LockSupport.parkNanos(minOf(w, 500_000)) }
            count[which]++
            when (which) {
                0 -> if (nextInsert < ds.base.size) {
                    val id = nextInsert++
                    sink.send(Events.insert(++seq, id, ds.base.m.row(id), null, csr.row(id))); produced.getValue("insert").incrementAndGet()
                }
                1 -> { val id = liveOld(); deleted.set(id); sink.send(Events.delete(++seq, id)); produced.getValue("delete").incrementAndGet() }
                else -> {
                    val id = liveOld()
                    val own = csr.row(id)
                    val ev = if (own.isEmpty() || rnd.nextBoolean()) Events.setAttrs(++seq, id, intArrayOf(topTags[rnd.nextInt(topTags.size)]), IntArray(0))
                    else Events.setAttrs(++seq, id, IntArray(0), intArrayOf(own[rnd.nextInt(own.size)]))
                    sink.send(ev); produced.getValue("set_attrs").incrementAndGet()
                }
            }
        }
        sink.flush()
    }

    /** Events that were due by [elapsedS] seconds. */
    fun intended(elapsedS: Double) = (insertRate * elapsedS).toLong() + (deleteRate * elapsedS).toLong() + (attrRate * elapsedS).toLong()
}

/**
 * exp5 / exp9 / exp6: replays the constructed workload against a copy of the base-item index through
 * Kafka (or the in-process twin), under each attribute path A1/A2/A3 and each SetAttrs rate, while
 * an open-loop query load runs; samples filtered recall against exact answers over the live set at
 * checkpoints.
 */
object StreamBench {
    fun run(a: Args): Int {
        val ds = FilteredDataset(a.path("data"), "yfcc-10M")
        val baseIndex = a.path("base-index")
        val work = a.path("work-index")
        val base = a.int("base", 9_000_000)
        val csr = ds.baseTags
        val ivfFiles = a.list("ivf", "")
        val queryIvf = a.int("query-ivf", 4096)
        val modes = a.list("modes", "A3,A2,A1").map { AttrMode.valueOf(it) }
        val rates = a.doubles("rates", "0,100,1000,10000")
        val qps = a.list("qps", "10").map { it.toDouble() }
        val duration = a.double("duration", 120.0)
        val checkpointS = a.double("checkpoint", 30.0)
        val checkQueries = a.int("check-queries", 200)
        val via = a.str("via", "kafka")
        val bootstrap = a.str("bootstrap", "127.0.0.1:19092")
        val out = JsonlWriter(a.path("out"))
        val priv = ds.queries("private")
        val qStrategies = a.list("query-strategies", "S2,S4")
        for (rep in 1..a.int("repeats", 1)) for (mode in modes) for (rate in rates) for (q in qps) {
            val loadStart = Machine.load()
            val tc = System.nanoTime()
            deleteRecursively(work); Files.createDirectories(work); copyRecursively(baseIndex, work)
            val copyS = (System.nanoTime() - tc) / 1e9
            val ivfs = ivfFiles.map { f -> IvfIndex.load(Path.of(f)).let { full ->
                val asg = full.assignmentCopy(); for (r in base until asg.size) asg[r] = -1
                IvfIndex(full.k, full.d, full.centroids, asg)
            } }
            val clusterFields = ivfs.associateBy { VectorIndex.clusterField(it.k) }
            val attrs = AttributeStore(csr.ncol, ds.base.size).also { it.load(csr, 0 until base) }
            val idx = VectorIndex(work, IndexSchema(clusterFields = clusterFields.keys.toList()), create = false,
                refreshMs = a.long("refresh-ms", 1000), ramBufferMb = 256.0, lagSampleEvery = 1, mergeWorkers = 1, deletesPctAllowed = a.double("deletes-pct", 20.0))
            idx.refreshNow(); idx.startRefresher()
            val ivf = ivfs.firstOrNull { it.k == queryIvf }
            val applier = Applier(idx, attrs, ivf, mode, clusterFields).also { it.seed(0 until base); it.vectorOf = { id -> ds.base.luceneBytes(id) to null } }
            val e = Engine(ds.base, attrs, object : SearcherSource {
                override fun <T> withSearcher(body: (IndexSearcher) -> T): T = idx.withSearcher(body)
            }, ivfs.associateBy { it.k }, when (mode) { AttrMode.A1 -> FilterMode.TERMS; AttrMode.A2 -> FilterMode.DOCVALUES; AttrMode.A3 -> FilterMode.EXTERNAL })
            val strategies = qStrategies.associateWith { s ->
                when (s) {
                    "S2" -> LuceneFilteredHnsw(e, "S2", 0) to SearchBudget(ef = a.int("ef", 64))
                    "S3" -> LuceneFilteredHnsw(e, "S3", 60) to SearchBudget(ef = a.int("ef", 64))
                    "S4" -> IvfIntersect(e, queryIvf) to SearchBudget(nprobe = a.int("nprobe", 32))
                    "P" -> PlannedStrategy(e, facetindex.planner.Planner.load(a.path("planner-model"), a.double("planner-knob", 0.0))) to SearchBudget()
                    else -> PreFilterBruteForce(e) to SearchBudget()
                }
            }
            val s0 = PreFilterBruteForce(e)
            val topic = "catalog-events"
            val consumer: KafkaCatalogConsumer?
            val sink: EventSink
            if (via == "kafka") {
                Topics.recreate(bootstrap, topic, 4)
                sink = KafkaSink(bootstrap, topic)
                consumer = KafkaCatalogConsumer(bootstrap, topic, "exp5-${System.nanoTime()}", applier).start()
            } else { sink = DirectSink(applier); consumer = null }
            val insertRate = if (rate > 0 || a.flag("churn-at-zero", true)) a.double("insert-rate", 100.0) else 0.0
            val wl = YfccWorkload(ds, csr, base, insertRate, a.double("delete-rate", 100.0).takeIf { insertRate > 0 } ?: 0.0, rate, a.long("seed", 5L) + rep)
            idx.merges.reset(); idx.lag.reset()
            val bytes0 = idx.bytesOnDisk
            val t0 = System.nanoTime()
            val gen = Thread({ wl.run(sink, duration) }, "workload").apply { isDaemon = true; start() }
            // Open-loop query load, alternating the strategies.
            val latency = qStrategies.associateWith { ConcurrentHistogram(3) }
            val loop = OpenLoop(q, 4)
            val loadThread = Thread({
                loop.run(duration) { i, record, due ->
                    val name = qStrategies[(i % qStrategies.size).toInt()]
                    val qi = ((i * 7919) % priv.n).toInt()
                    val (s, b) = strategies.getValue(name)
                    s.search(priv.vector(qi), Predicate(priv.tagsOf(qi)), 10, b)
                    // From the intended send time, so queueing behind slow queries counts.
                    if (record) latency.getValue(name).recordValue(((System.nanoTime() - due) / 1000).coerceAtLeast(0))
                }
            }, "query-load").apply { isDaemon = true; start() }
            // Checkpoints: recall of each strategy against exact answers over the live set.
            val checkpoints = ArrayList<Map<String, Any?>>()
            var nextCp = checkpointS
            val rnd = SplittableRandom(rep * 31L)
            while (gen.isAlive) {
                val el = (System.nanoTime() - t0) / 1e9
                if (el >= nextCp) {
                    nextCp += checkpointS
                    val hits = HashMap<String, Long>(); var poss = 0L
                    repeat(checkQueries) {
                        val qi = rnd.nextInt(priv.n)
                        val pred = Predicate(priv.tagsOf(qi))
                        val truth = s0.search(priv.vector(qi), pred, 10, SearchBudget()).rows.toSet()
                        poss += truth.size
                        for ((n, sb) in strategies) hits.merge(n, sb.first.search(priv.vector(qi), pred, 10, sb.second).rows.count { it in truth }.toLong(), Long::plus)
                    }
                    checkpoints += linkedMapOf("t_s" to el, "applied" to applier.applied.get(), "produced" to wl.seq,
                        "recall_at_10" to hits.mapValues { it.value.toDouble() / poss.coerceAtLeast(1) }, "segments" to idx.segmentCount(), "live" to attrs.liveCount())
                }
                Thread.sleep(200)
            }
            loadThread.join()
            val genEnd = (System.nanoTime() - t0) / 1e9
            val backlogGen = wl.seq - (applier.applied.get() + applier.skipped.get())
            // Drain: how long until the consumer catches up (bounded).
            val drainStart = System.nanoTime()
            val produced = wl.seq
            while (applier.applied.get() + applier.skipped.get() < produced && (System.nanoTime() - drainStart) < a.double("drain-max", 120.0) * 1e9) Thread.sleep(20)
            val drainS = (System.nanoTime() - drainStart) / 1e9
            val backlogAtEnd = produced - (applier.applied.get() + applier.skipped.get())
            idx.refreshNow()
            val elapsed = (System.nanoTime() - t0) / 1e9
            val row = linkedMapOf<String, Any?>(
                "experiment" to a.str("experiment", "exp5"), "workload" to "constructed: YFCC filtered streaming (not a competition track)",
                "mode" to mode.name, "set_attrs_rate" to rate, "insert_rate" to insertRate, "delete_rate" to (if (insertRate > 0) a.double("delete-rate", 100.0) else 0.0),
                "via" to via, "duration_s" to duration, "query_qps" to q, "refresh_ms" to a.long("refresh-ms", 1000),
                "produced" to wl.produced.mapValues { it.value.get() }, "applied" to applier.counters(),
                "achieved_apply_rate" to applier.applied.get() / elapsed, "achieved_set_attrs_rate" to applier.byType.getValue("set_attrs").get() / genEnd,
                "backlog_at_generator_end" to backlogGen, "drain_s" to drainS, "backlog_after_drain" to backlogAtEnd,
                "apply_lag_us" to applier.applyLagMicros.summary(), "attr_apply_lag_us" to applier.attrApplyLagMicros.summary(),
                "lucene_visibility_lag_us" to idx.lag.histogramMicros.summary(),
                "freshness_note" to "A3 attribute changes are visible when applied (attr_apply_lag); Lucene writes become visible at the next NRT refresh (lucene_visibility_lag, from produce time)",
                "merge_s" to idx.merges.mergeNanos.get() / 1e9, "merge_s_per_min" to idx.merges.mergeNanos.get() / 1e9 / (elapsed / 60), "merges" to idx.merges.mergeCount.get(),
                "index_bytes_start" to bytes0, "index_bytes_end" to idx.bytesOnDisk, "rss_bytes" to Machine.rssBytes(), "attr_store_bytes" to attrs.bytes(),
                "query_latency_us" to latency.mapValues { it.value.summary() }, "query_open_loop_us" to loop.latencyUs.summary(), "query_errors" to loop.errors.get(),
                "query_last_error" to loop.lastError?.toString(), "checkpoints" to checkpoints, "copy_s" to copyS, "consumer_error" to consumer?.error?.toString(),
                "repeat" to rep, "repeats" to a.int("repeats", 1), "machine" to Machine.info, "load_start" to loadStart, "load_end" to Machine.load(),
            )
            out.write(row)
            println("mode=$mode rate=$rate qps=$q applied=${applier.counters()} drain=${"%.1f".format(drainS)}s backlog=$backlogAtEnd vis_p99=${idx.lag.histogramMicros.getValueAtPercentile(99.0)}us cp=${checkpoints.lastOrNull()?.get("recall_at_10")}")
            consumer?.close(); sink.close()
            idx.close()
        }
        out.close()
        deleteRecursively(work)
        return 0
    }
}
