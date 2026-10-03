package facetindex.index

import facetindex.util.deleteRecursively
import facetindex.util.dirBytes
import org.HdrHistogram.ConcurrentHistogram
import org.apache.lucene.codecs.Codec
import org.apache.lucene.codecs.KnnVectorsFormat
import org.apache.lucene.codecs.lucene104.Lucene104Codec
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.IntField
import org.apache.lucene.document.KnnByteVectorField
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.LongField
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.StringField
import org.apache.lucene.index.ConcurrentMergeScheduler
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.MergePolicy
import org.apache.lucene.index.MergeScheduler
import org.apache.lucene.index.Term
import org.apache.lucene.index.TieredMergePolicy
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.ReferenceManager
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Which fields documents carry. */
data class IndexSchema(
    /** A1: tags as indexed StringField terms. */
    val tagTerms: Boolean = true,
    /** A2: tags as sorted ints in a BinaryDocValues field. */
    val tagDocValues: Boolean = true,
    /** IVF cluster ids as IntField, one field per configuration (for S4-L). */
    val clusterFields: List<String> = emptyList(),
    /** Integer range attributes as LongField (points plus doc values). */
    val rangeFields: List<String> = emptyList(),
    val hnswM: Int = 16,
    val hnswBeam: Int = 100,
    val byteVectors: Boolean = true,
    val maxDims: Int = 1024,
)

/** One item as the index sees it. */
class IndexItem(
    val row: Int,
    val byteVec: ByteArray? = null,
    val floatVec: FloatArray? = null,
    val tags: IntArray = IntArray(0),
    val clusters: Map<String, Int> = emptyMap(),
    val ranges: Map<String, Long> = emptyMap(),
)

/**
 * The Lucene index: one document per live item, sorted by `row` so that within every segment docids
 * increase with row id (and in a force-merged index with no deletes, docid == row).
 *
 * Fields: `id` (keyword term, for updateDocument and deletes), `row` (numeric doc value, also the
 * index sort), `tag` (A1 terms), `tagdv` (A2 binary doc value), `cluster_<C>` (IntField), range
 * fields (LongField), and `vec` (KnnByteVectorField, EUCLIDEAN; uint8 values shifted to int8).
 *
 * Writes come from one thread at a time in streaming use (bulk builds may add from several). A
 * background thread refreshes the [SearcherManager] every [refreshMs]; [lag] measures write-to-visible
 * time for sampled writes. Ported from an earlier project's index wrapper: the merge timer, the lag
 * sampler and the scheduled refresher are unchanged.
 */
class VectorIndex(
    val path: Path,
    val schema: IndexSchema,
    create: Boolean,
    private val refreshMs: Long = 1000,
    ramBufferMb: Double = 256.0,
    lagSampleEvery: Int = 100,
    mergeWorkers: Int = 1,
    deletesPctAllowed: Double = 20.0,
) : AutoCloseable {
    val merges = TimedMergeScheduler()
    private val mergeExec: ExecutorService? = if (mergeWorkers > 1) Executors.newFixedThreadPool(mergeWorkers) { r -> Thread(r, "hnsw-merge").apply { isDaemon = true } } else null
    private val directory = FSDirectory.open(path.also { Files.createDirectories(it) })
    val writer: IndexWriter = IndexWriter(directory, IndexWriterConfig().apply {
        openMode = if (create) IndexWriterConfig.OpenMode.CREATE else IndexWriterConfig.OpenMode.APPEND
        ramBufferSizeMB = ramBufferMb
        mergeScheduler = merges
        indexSort = Sort(SortField(F_ROW, SortField.Type.LONG))
        codec = codec(schema, mergeWorkers, mergeExec)
        mergePolicy = TieredMergePolicy().apply { this.deletesPctAllowed = deletesPctAllowed }
    })
    val lag = VisibilityLag(writer, lagSampleEvery)
    val searcherManager = SearcherManager(writer, object : SearcherFactory() {
        override fun newSearcher(reader: IndexReader, previousReader: IndexReader?): IndexSearcher =
            IndexSearcher(reader).apply { queryCache = null } // external-attribute filters must never be cached
    }).also { it.addListener(lag) }
    private var refresher: ScheduledExecutorService? = null
    val docsWritten = AtomicLong()
    private val similarity = VectorSimilarityFunction.EUCLIDEAN

    fun document(item: IndexItem): Document = Document().apply {
        add(StringField(F_ID, item.row.toString(), Field.Store.NO))
        add(NumericDocValuesField(F_ROW, item.row.toLong()))
        if (schema.tagTerms) for (t in item.tags) add(StringField(F_TAG, t.toString(), Field.Store.NO))
        if (schema.tagDocValues) add(BinaryDocValuesField(F_TAGDV, encodeTags(item.tags)))
        for (f in schema.clusterFields) item.clusters[f]?.let { add(IntField(f, it, Field.Store.NO)) }
        for (f in schema.rangeFields) item.ranges[f]?.let { add(LongField(f, it, Field.Store.NO)) }
        when {
            item.byteVec != null -> add(KnnByteVectorField(F_VEC, item.byteVec, similarity))
            item.floatVec != null -> add(KnnFloatVectorField(F_VEC, item.floatVec, similarity))
        }
    }

    fun add(item: IndexItem): Long {
        val seq = writer.addDocument(document(item))
        docsWritten.incrementAndGet()
        lag.onWrite(seq)
        return seq
    }

    /** A1 attribute update: delete and re-add the whole document (the vector goes back into the graph). */
    fun upsert(item: IndexItem): Long {
        val seq = writer.updateDocument(Term(F_ID, item.row.toString()), document(item))
        docsWritten.incrementAndGet()
        lag.onWrite(seq)
        return seq
    }

    /** A2 attribute update: rewrite only the tag doc value (no re-indexing of the vector). */
    fun updateTagDocValue(row: Int, tags: IntArray): Long {
        val seq = writer.updateBinaryDocValue(Term(F_ID, row.toString()), F_TAGDV, encodeTags(tags))
        lag.onWrite(seq)
        return seq
    }

    fun delete(row: Int): Long {
        val seq = writer.deleteDocuments(Term(F_ID, row.toString()))
        lag.onWrite(seq)
        return seq
    }

    fun commit() = writer.commit()

    fun forceMerge(segments: Int = 1) = writer.forceMerge(segments, true)

    fun startRefresher() {
        if (refresher != null || refreshMs <= 0) return
        refresher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nrt-refresh").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching { searcherManager.maybeRefresh() } }, refreshMs, refreshMs, TimeUnit.MILLISECONDS) }
    }

    fun stopRefresher() {
        refresher?.let { it.shutdownNow(); it.awaitTermination(5, TimeUnit.SECONDS) }
        refresher = null
    }

    fun refreshNow() = searcherManager.maybeRefreshBlocking()

    inline fun <T> withSearcher(body: (IndexSearcher) -> T): T {
        val s = searcherManager.acquire()
        try {
            return body(s)
        } finally {
            searcherManager.release(s)
        }
    }

    val bytesOnDisk: Long get() = dirBytes(path)

    fun segmentCount(): Int = withSearcher { it.indexReader.leaves().size }

    override fun close() {
        stopRefresher()
        runCatching { searcherManager.close() }
        try {
            writer.close()
        } finally {
            directory.close()
            mergeExec?.shutdownNow()
        }
    }

    fun closeAndDelete() {
        try {
            close()
        } finally {
            deleteRecursively(path)
        }
    }

    companion object {
        const val F_ID = "id"
        const val F_ROW = "row"
        const val F_TAG = "tag"
        const val F_TAGDV = "tagdv"
        const val F_VEC = "vec"

        fun clusterField(c: Int) = "cluster_$c"

        fun codec(schema: IndexSchema, mergeWorkers: Int, mergeExec: ExecutorService?): Codec {
            val hnsw = Lucene99HnswVectorsFormat(schema.hnswM, schema.hnswBeam, maxOf(1, mergeWorkers), mergeExec)
            val format: KnnVectorsFormat = if (schema.maxDims <= 1024) hnsw else HighDimFormat(hnsw, schema.maxDims)
            return object : Lucene104Codec() {
                override fun getKnnVectorsFormatForField(field: String): KnnVectorsFormat = format
            }
        }

        /** Sorted tag ids as little-endian ints (A2). */
        fun encodeTags(tags: IntArray): BytesRef {
            val sorted = tags.sortedArray()
            val b = ByteArray(sorted.size * 4)
            for (i in sorted.indices) {
                val v = sorted[i]
                b[4 * i] = v.toByte(); b[4 * i + 1] = (v ushr 8).toByte(); b[4 * i + 2] = (v ushr 16).toByte(); b[4 * i + 3] = (v ushr 24).toByte()
            }
            return BytesRef(b)
        }

        fun decodeTagAt(b: BytesRef, i: Int): Int {
            val o = b.offset + 4 * i
            val a = b.bytes
            return (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8) or ((a[o + 2].toInt() and 0xFF) shl 16) or ((a[o + 3].toInt() and 0xFF) shl 24)
        }

        /** Opens an existing index read-only (static benchmarks). */
        fun openReader(path: Path): DirectoryReader = DirectoryReader.open(FSDirectory.open(path))
    }
}

/**
 * Lucene's vector formats cap dimensions at 1,024 by default; the 4,096-dimension arXiv embeddings
 * need a per-field override. This wrapper only raises [getMaxDimensions].
 */
class HighDimFormat(private val delegate: KnnVectorsFormat, private val maxDims: Int) : KnnVectorsFormat(delegate.name) {
    override fun fieldsWriter(state: org.apache.lucene.index.SegmentWriteState) = delegate.fieldsWriter(state)
    override fun fieldsReader(state: org.apache.lucene.index.SegmentReadState) = delegate.fieldsReader(state)
    override fun getMaxDimensions(fieldName: String) = maxDims
}

/** ConcurrentMergeScheduler that times every merge. */
class TimedMergeScheduler : ConcurrentMergeScheduler() {
    val mergeNanos = AtomicLong()
    val mergeCount = AtomicLong()
    val maxMergeNanos = AtomicLong()

    override fun doMerge(mergeSource: MergeScheduler.MergeSource, merge: MergePolicy.OneMerge) {
        val t0 = System.nanoTime()
        try {
            super.doMerge(mergeSource, merge)
        } finally {
            val dt = System.nanoTime() - t0
            mergeNanos.addAndGet(dt)
            mergeCount.incrementAndGet()
            maxMergeNanos.accumulateAndGet(dt, ::maxOf)
        }
    }

    fun reset() {
        mergeNanos.set(0); mergeCount.set(0); maxMergeNanos.set(0)
    }
}

/**
 * Write-to-visible lag, measured with sequence numbers. One in [sampleEvery] writes records
 * (sequence number, time applied). Before each refresh the listener captures the writer's highest
 * completed sequence number; every write at or below it is in the reader that refresh opens, so after
 * a successful refresh each pending sample up to the captured number is recorded as now minus time
 * applied. [onWriteAt] lets the stream layer record from the event's produce time instead.
 */
class VisibilityLag(private val writer: IndexWriter, private val sampleEvery: Int) : ReferenceManager.RefreshListener {
    val histogramMicros = ConcurrentHistogram(3)
    private val pending = ArrayDeque<LongArray>()
    private var writes = 0L

    @Volatile
    private var captured = -1L

    fun onWrite(seq: Long) = onWriteAt(seq, System.nanoTime())

    /** [originNanos] is on the System.nanoTime clock. */
    fun onWriteAt(seq: Long, originNanos: Long) {
        if (writes++ % sampleEvery != 0L) return
        synchronized(pending) { pending.addLast(longArrayOf(seq, originNanos)) }
    }

    override fun beforeRefresh() {
        captured = writer.maxCompletedSequenceNumber
    }

    override fun afterRefresh(didRefresh: Boolean) {
        if (!didRefresh) return
        val now = System.nanoTime()
        synchronized(pending) {
            while (pending.isNotEmpty() && pending.first()[0] <= captured) {
                histogramMicros.recordValue(((now - pending.removeFirst()[1]) / 1000).coerceAtLeast(0))
            }
        }
    }

    fun pendingCount() = synchronized(pending) { pending.size }

    fun reset() {
        histogramMicros.reset()
        synchronized(pending) { pending.clear() }
    }
}
