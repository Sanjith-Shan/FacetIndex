package facetindex.stream

import com.google.protobuf.ByteString
import facetindex.api.v1.CatalogEvent
import facetindex.api.v1.DeleteRequest
import facetindex.api.v1.SetAttrsRequest
import facetindex.api.v1.UpsertRequest
import facetindex.attrs.AttributeStore
import facetindex.data.QueryVector
import facetindex.data.U8Store
import facetindex.index.IndexItem
import facetindex.index.VectorIndex
import facetindex.ivf.IvfIndex
import org.HdrHistogram.ConcurrentHistogram
import java.util.concurrent.atomic.AtomicLong

/** How an attribute change reaches the filter Lucene sees. */
enum class AttrMode {
    /** Tags as indexed terms: a change is updateDocument, which re-inserts the vector into the graph. */
    A1,
    /** Tags in a binary doc value: a change is updateBinaryDocValue, no re-indexing of the vector. */
    A2,
    /** Tags in the external store only: a change is an in-place bitmap and row update. */
    A3,
}

object Events {
    fun insert(seq: Long, id: Int, u8: ByteArray?, f32: FloatArray?, tags: IntArray): CatalogEvent = CatalogEvent.newBuilder()
        .setInsert(UpsertRequest.newBuilder().setId(id).setVersion(seq).apply {
            if (u8 != null) vectorU8 = ByteString.copyFrom(u8)
            if (f32 != null) addAllVectorF32(f32.asList())
            addAllTags(tags.asList())
        }).setEventSeq(seq).setProducedAtMicros(nowMicros()).build()

    fun delete(seq: Long, id: Int): CatalogEvent = CatalogEvent.newBuilder()
        .setDelete(DeleteRequest.newBuilder().setId(id).setVersion(seq)).setEventSeq(seq).setProducedAtMicros(nowMicros()).build()

    fun setAttrs(seq: Long, id: Int, add: IntArray, remove: IntArray): CatalogEvent = CatalogEvent.newBuilder()
        .setSetAttrs(SetAttrsRequest.newBuilder().setId(id).setVersion(seq).addAllAdd(add.asList()).addAllRemove(remove.asList()))
        .setEventSeq(seq).setProducedAtMicros(nowMicros()).build()

    fun idOf(e: CatalogEvent): Int = when (e.bodyCase) {
        CatalogEvent.BodyCase.INSERT -> e.insert.id
        CatalogEvent.BodyCase.DELETE -> e.delete.id
        CatalogEvent.BodyCase.SET_ATTRS -> e.setAttrs.id
        else -> -1
    }

    /** Wall clock in microseconds (producer and consumer share one machine, so one clock). */
    fun nowMicros(): Long {
        val i = java.time.Instant.now()
        return i.epochSecond * 1_000_000 + i.nano / 1000
    }
}

/**
 * Applies catalog events to the Lucene index, the attribute store and the IVF lists. One thread
 * calls [apply] (the Kafka consumer or the in-process replayer); [applyInsertsParallel] may fan a run
 * of inserts out to several threads, since Lucene's IndexWriter is thread-safe and the inserts in one
 * run have distinct ids.
 *
 * Idempotent by (id, version): each id remembers the version (the event's position in the source
 * workload) of the last event applied to it, and an event at or below that version is skipped. A
 * consumer that crashes after applying part of a batch but before committing its offsets re-reads
 * the batch on restart, skips what it already applied and converges to the same state.
 */
class Applier(
    val index: VectorIndex?,
    val attrs: AttributeStore?,
    val ivf: IvfIndex?,
    val mode: AttrMode,
    private val clusterFields: Map<String, IvfIndex> = emptyMap(),
    /** Receives vectors whose row is not (or differs from) the base file's. */
    private val overlay: facetindex.data.OverlayStore? = null,
) {
    @Volatile
    private var versions = LongArray(1 shl 20) { -1 }
    private val versionLock = Any()
    val applied = AtomicLong()
    val skipped = AtomicLong()
    val byType = mapOf("insert" to AtomicLong(), "delete" to AtomicLong(), "set_attrs" to AtomicLong())

    /** Produce-to-applied time (microseconds); for A3 attribute changes this is also produce-to-visible. */
    val applyLagMicros = ConcurrentHistogram(3)
    /** Produce-to-applied time of attribute changes only. */
    val attrApplyLagMicros = ConcurrentHistogram(3)

    /** Highest event sequence applied so far (events arrive in sequence order within a partition). */
    val lastSeq = AtomicLong(-1)

    private fun versionOf(id: Int): Long = versions.let { if (id < it.size) it[id] else -1 }

    private fun setVersion(id: Int, v: Long) {
        if (id >= versions.size) synchronized(versionLock) {
            if (id >= versions.size) {
                val n = versions.copyOf(maxOf(id + 1, versions.size * 2))
                java.util.Arrays.fill(n, versions.size, n.size, -1)
                versions = n
            }
        }
        versions[id] = v
    }

    /** Marks ids as present at version 0 (the initial snapshot). */
    fun seed(ids: IntRange) {
        setVersion(ids.last, 0)
        java.util.Arrays.fill(versions, ids.first, ids.last + 1, 0)
    }

    fun apply(e: CatalogEvent) {
        val id = Events.idOf(e)
        if (id < 0) return
        val version = e.eventSeq
        if (version <= versionOf(id)) { skipped.incrementAndGet(); return }
        val origin = originNanos(e)
        when (e.bodyCase) {
            CatalogEvent.BodyCase.INSERT -> doInsert(e.insert, origin)
            CatalogEvent.BodyCase.DELETE -> {
                attrs?.delete(id)
                ivf?.delete(id)
                for (x in clusterFields.values) if (x !== ivf) x.delete(id)
                index?.let { it.lag.onWriteAt(it.delete(id), origin) }
                byType.getValue("delete").incrementAndGet()
            }
            CatalogEvent.BodyCase.SET_ATTRS -> {
                val s = e.setAttrs
                val add = s.addList.toIntArray(); val remove = s.removeList.toIntArray()
                val a = attrs ?: error("attribute updates need the attribute store")
                if (a.setAttrs(id, add, remove)) when (mode) {
                    AttrMode.A1 -> index?.let { it.lag.onWriteAt(it.upsert(itemFor(id, a.tagsOf(id))), origin) }
                    AttrMode.A2 -> index?.let { it.lag.onWriteAt(it.updateTagDocValue(id, a.tagsOf(id)), origin) }
                    AttrMode.A3 -> {}
                }
                attrApplyLagMicros.recordValue(lagMicros(e))
                byType.getValue("set_attrs").incrementAndGet()
            }
            else -> return
        }
        setVersion(id, version)
        applyLagMicros.recordValue(lagMicros(e))
        applied.incrementAndGet()
        lastSeq.accumulateAndGet(version, ::maxOf)
    }

    private fun lagMicros(e: CatalogEvent) = (Events.nowMicros() - e.producedAtMicros).coerceAtLeast(0)

    private fun originNanos(e: CatalogEvent): Long = System.nanoTime() - (Events.nowMicros() - e.producedAtMicros).coerceAtLeast(0) * 1000

    /** Vectors of live rows, needed to rebuild an A1 document; set by the workload owner. */
    var vectorOf: ((Int) -> Pair<ByteArray?, FloatArray?>)? = null

    private fun itemFor(id: Int, tags: IntArray): IndexItem {
        val (u8, f32) = vectorOf?.invoke(id) ?: error("A1 updates need vectorOf")
        return IndexItem(id, u8, f32, tags, clusterFields.mapValues { it.value.clusterOf(id) })
    }

    private fun doInsert(u: UpsertRequest, origin: Long) {
        val id = u.id
        val tags = u.tagsList.toIntArray()
        val u8 = if (!u.vectorU8.isEmpty) u.vectorU8.toByteArray() else null
        val f32 = if (u.vectorF32Count > 0) u.vectorF32List.toFloatArray() else null
        attrs?.insert(id, tags)
        val q: QueryVector? = u8?.let { QueryVector.U8(it) } ?: f32?.let { QueryVector.F32(it) }
        if (q != null) {
            overlay?.put(id, q)
            ivf?.insert(id, q)
            for (x in clusterFields.values) if (x !== ivf) x.insert(id, q)
        }
        index?.let { idx ->
            val item = IndexItem(id, u8?.copyOf()?.let { U8Store.toLuceneBytes(it) }, f32, tags, clusterFields.mapValues { it.value.clusterOf(id) })
            val seq = if (versionOf(id) >= 0) idx.upsert(item) else idx.add(item)
            idx.lag.onWriteAt(seq, origin)
        }
        byType.getValue("insert").incrementAndGet()
    }

    /**
     * Applies a list of events in order, except that each maximal run of consecutive inserts is
     * applied by [threads] threads. Used by the runbook replay, whose insert batches are large.
     */
    fun applyBatch(events: List<CatalogEvent>, threads: Int, pool: java.util.concurrent.ExecutorService?) {
        if (threads <= 1 || pool == null) { events.forEach { apply(it) }; return }
        var i = 0
        while (i < events.size) {
            if (events[i].bodyCase != CatalogEvent.BodyCase.INSERT) { apply(events[i]); i++; continue }
            var j = i
            while (j < events.size && events[j].bodyCase == CatalogEvent.BodyCase.INSERT) j++
            val run = events.subList(i, j)
            if (attrs != null || ivf != null) run.forEach { apply(it) } // single-writer structures
            else {
                val chunk = (run.size + threads - 1) / threads
                run.chunked(maxOf(1, chunk)).map { part -> pool.submit { part.forEach { apply(it) } } }.forEach { it.get() }
            }
            i = j
        }
    }

    fun counters(): Map<String, Long> = mapOf("applied" to applied.get(), "skipped" to skipped.get()) + byType.mapValues { it.value.get() }
}
