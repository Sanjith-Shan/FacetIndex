package facetindex.attrs

import facetindex.data.Csr
import org.roaringbitmap.RoaringBitmap
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The external attribute store (A3): per-tag RoaringBitmaps plus per-item tag arrays, outside
 * Lucene, updated in place.
 *
 * Per-item tags live in one flat [IntArray]. Row r owns `slots[start[r] until start[r] + cap[r]]`, of
 * which the first `len[r]` hold its sorted tag ids. Each row is created with [SLACK] spare slots, so
 * most tag additions are in place; a row that outgrows its slots is moved to the end of the array.
 *
 * Concurrency: one writer thread, many readers.
 * - Per-row tags are guarded by a per-row seqlock (an [AtomicIntegerArray] counter): the writer makes
 *   it odd, fences, mutates the row in place, then publishes an even value with a release store.
 *   Readers retry until they see the same even value before and after the read. This is the
 *   availability-store pattern ported from an earlier project of mine, with tags in place of nights.
 * - Per-tag bitmaps are not thread-safe, so each tag maps to one of [STRIPES] read-write locks.
 *   Readers hold the read lock only while they intersect or copy a bitmap (microseconds); the writer
 *   takes one write lock at a time, so readers holding two read locks cannot deadlock with it.
 *
 * A deleted row is removed from every tag bitmap, so every bitmap holds only live rows and no
 * separate liveness check is needed on the read path.
 */
class AttributeStore(val nTags: Int, initialCapacity: Int) {
    private var capacity = initialCapacity
    @Volatile private var slots = IntArray(0)
    private var used = 0
    @Volatile private var start = IntArray(capacity)
    @Volatile private var cap = ShortArray(capacity)
    @Volatile private var len = ShortArray(capacity)
    @Volatile private var seq = AtomicIntegerArray(capacity)
    private val postings = arrayOfNulls<RoaringBitmap>(nTags)
    private val locks = Array(STRIPES) { ReentrantReadWriteLock() }
    val live = RoaringBitmap()
    private val liveLock = ReentrantReadWriteLock()

    @Volatile
    var version = 0L
        private set

    private fun lockOf(tag: Int) = locks[tag and (STRIPES - 1)]

    // ------------------------------------------------------------------ bulk load (before readers)

    /** Loads rows [rows] of [csr] as live items (row id = CSR row index). Not thread-safe. */
    fun load(csr: Csr, rows: IntRange = 0 until csr.nrow) {
        ensureCapacity(rows.last + 1)
        val total = rows.sumOf { csr.rowLength(it).toLong() + SLACK }
        require(used + total < Int.MAX_VALUE) { "too many tag slots: ${used + total}" }
        slots = slots.copyOf((used + total).toInt())
        val perTag = IntArray(nTags)
        for (r in rows) for (i in csr.offsets[r] until csr.offsets[r + 1]) perTag[csr.indices[i]]++
        // Build each bitmap from a sorted int array in one go (much faster than per-row adds).
        val buckets = Array(nTags) { IntArray(perTag[it]) }
        val fill = IntArray(nTags)
        for (r in rows) {
            val n = csr.rowLength(r)
            start[r] = used
            cap[r] = (n + SLACK).toShort()
            len[r] = n.toShort()
            System.arraycopy(csr.indices, csr.offsets[r], slots, used, n)
            used += n + SLACK
            for (i in csr.offsets[r] until csr.offsets[r + 1]) {
                val t = csr.indices[i]
                buckets[t][fill[t]++] = r
            }
        }
        for (t in 0 until nTags) if (buckets[t].isNotEmpty()) {
            val bm = postings[t] ?: RoaringBitmap().also { postings[t] = it }
            bm.addN(buckets[t], 0, buckets[t].size)
            bm.runOptimize()
        }
        live.add(rows.first.toLong(), rows.last.toLong() + 1)
        version++
    }

    private fun ensureCapacity(n: Int) {
        if (n <= capacity) return
        val c = maxOf(n, capacity + capacity / 2)
        start = start.copyOf(c); cap = cap.copyOf(c); len = len.copyOf(c)
        val s = AtomicIntegerArray(c)
        for (i in 0 until capacity) s.set(i, seq.get(i))
        seq = s
        capacity = c
    }

    // ------------------------------------------------------------------ writer (one thread)

    /** Adds a new live row with the given tags (or replaces a row's tags if it exists). */
    fun insert(row: Int, tags: IntArray) {
        ensureCapacity(row + 1)
        val sorted = tags.distinct().sorted().toIntArray()
        if (isLive(row)) {
            // Replace: the same as adding the new tags and removing every current one not in them.
            setAttrs(row, sorted, tagsOf(row))
            return
        }
        allocate(row, sorted.size)
        writeRow(row) {
            System.arraycopy(sorted, 0, slots, start[row], sorted.size)
            len[row] = sorted.size.toShort()
        }
        for (t in sorted) lockOf(t).write { (postings[t] ?: RoaringBitmap().also { postings[t] = it }).add(row) }
        liveLock.write { live.add(row) }
        version++
    }

    /** Removes a row: drops it from every tag bitmap and marks it dead. Returns false if unknown. */
    fun delete(row: Int): Boolean {
        if (!isLive(row)) return false
        val tags = tagsOf(row)
        liveLock.write { live.remove(row) }
        for (t in tags) lockOf(t).write { postings[t]?.remove(row) }
        writeRow(row) { len[row] = 0 }
        version++
        return true
    }

    /** Adds and removes tags on a live row in place. Returns false if the row is not live. */
    fun setAttrs(row: Int, add: IntArray, remove: IntArray): Boolean {
        if (!isLive(row)) return false
        val cur = tagsOf(row).toMutableSet()
        val toAdd = add.filter { cur.add(it) }
        val toRemove = remove.filter { it !in add && cur.remove(it) }
        if (toAdd.isEmpty() && toRemove.isEmpty()) return true
        val next = cur.sorted().toIntArray()
        setTags(row, next)
        for (t in toAdd) lockOf(t).write { (postings[t] ?: RoaringBitmap().also { postings[t] = it }).add(row) }
        for (t in toRemove) lockOf(t).write { postings[t]?.remove(row) }
        version++
        return true
    }

    private fun setTags(row: Int, sorted: IntArray) {
        if (sorted.size > cap[row]) allocate(row, sorted.size)
        writeRow(row) {
            System.arraycopy(sorted, 0, slots, start[row], sorted.size)
            len[row] = sorted.size.toShort()
        }
    }

    /** Gives [row] room for [n] tags at the end of the slot array (old slots are abandoned). */
    private fun allocate(row: Int, n: Int) {
        val need = n + SLACK
        require(need <= Short.MAX_VALUE) { "row $row has too many tags: $n" }
        if (used + need > slots.size) {
            // Readers may hold the old array reference; they only read rows they validate with the
            // seqlock, and the copy keeps every existing row at the same offset.
            slots = slots.copyOf(maxOf(used + need, slots.size + slots.size / 8 + 1024))
        }
        writeRow(row) {
            val old = start[row]
            val oldLen = len[row].toInt()
            System.arraycopy(slots, old, slots, used, oldLen)
            start[row] = used
            cap[row] = need.toShort()
            used += need
        }
    }

    private inline fun writeRow(row: Int, body: () -> Unit) {
        val s = seq.get(row)
        seq.set(row, s + 1) // odd: write in progress
        VarHandle.storeStoreFence()
        try {
            body()
        } finally {
            seq.setRelease(row, s + 2) // even: publishes the stores above
        }
    }

    // ------------------------------------------------------------------ readers (any thread)

    private inline fun <T> readRow(row: Int, read: () -> T): T {
        while (true) {
            val s = seq.getAcquire(row)
            if (s and 1 == 1) {
                Thread.onSpinWait(); continue
            }
            val v = try {
                read()
            } catch (e: IndexOutOfBoundsException) {
                // A concurrent move can make a torn (start, len) pair point past the array; retry.
                if (seq.get(row) == s) throw e else continue
            }
            VarHandle.acquireFence()
            if (seq.get(row) == s) return v
        }
    }

    fun isLive(row: Int): Boolean = row in 0 until capacity && liveLock.read { live.contains(row) }

    fun tagsOf(row: Int): IntArray = readRow(row) {
        val st = start[row]
        slots.copyOfRange(st, st + len[row])
    }

    /** True if [row] carries every tag in [tags] (seqlock read of the row, binary search per tag). */
    fun hasAll(row: Int, tags: IntArray): Boolean = readRow(row) {
        val st = start[row]
        val end = st + len[row]
        val s = slots
        tags.all { t -> java.util.Arrays.binarySearch(s, st, end, t) >= 0 }
    }

    fun cardinality(tag: Int): Int = if (tag !in 0 until nTags) 0 else lockOf(tag).read { postings[tag]?.cardinality ?: 0 }

    fun liveCount(): Int = liveLock.read { live.cardinality }

    /** Exact number of live rows carrying every tag (andCardinality for two tags). */
    fun matchCount(tags: IntArray): Int = when (tags.size) {
        0 -> liveCount()
        1 -> cardinality(tags[0])
        2 -> withTwo(tags[0], tags[1]) { a, b -> if (a == null || b == null) 0 else RoaringBitmap.andCardinality(a, b) }
        else -> matching(tags).cardinality
    }

    /** A private copy of the live rows carrying every tag (no tags: every live row). */
    fun matching(tags: IntArray): RoaringBitmap = when (tags.size) {
        0 -> liveLock.read { live.clone() }
        1 -> lockOf(tags[0]).read { postings.getOrNull(tags[0])?.clone() ?: RoaringBitmap() }
        2 -> withTwo(tags[0], tags[1]) { a, b -> if (a == null || b == null) RoaringBitmap() else RoaringBitmap.and(a, b) }
        else -> {
            var acc = matching(intArrayOf(tags[0], tags[1]))
            for (i in 2 until tags.size) acc = lockOf(tags[i]).read { postings[tags[i]]?.let { RoaringBitmap.and(acc, it) } ?: RoaringBitmap() }
            acc
        }
    }

    /** Runs [body] on one tag's bitmap (or null) under its read lock. Do not keep the reference. */
    fun <T> withPosting(tag: Int, body: (RoaringBitmap?) -> T): T =
        if (tag !in 0 until nTags) body(null) else lockOf(tag).read { body(postings[tag]) }

    /**
     * Runs [body] with the bitmaps of one or two tags (null entries for tags nobody carries) under
     * their read locks. More than two tags are pre-intersected into a private bitmap.
     */
    fun <T> withPostings(tags: IntArray, body: (Array<RoaringBitmap?>) -> T): T = when (tags.size) {
        1 -> withPosting(tags[0]) { body(arrayOf(it)) }
        2 -> withTwo(tags[0], tags[1]) { a, b -> body(arrayOf(a, b)) }
        else -> body(arrayOf(matching(tags)))
    }

    private fun <T> withTwo(t1: Int, t2: Int, body: (RoaringBitmap?, RoaringBitmap?) -> T): T {
        if (t1 !in 0 until nTags || t2 !in 0 until nTags) return body(null, null)
        val l1 = lockOf(t1); val l2 = lockOf(t2)
        return l1.read { if (l1 === l2) body(postings[t1], postings[t2]) else l2.read { body(postings[t1], postings[t2]) } }
    }

    /** Approximate heap bytes: slots, row arrays and serialized bitmap sizes. */
    fun bytes(): Long {
        var b = slots.size * 4L + capacity * (4L + 2 + 2 + 4)
        for (t in 0 until nTags) b += lockOf(t).read { postings[t]?.serializedSizeInBytes()?.toLong() ?: 0L }
        return b
    }

    companion object {
        const val SLACK = 2
        const val STRIPES = 1024
    }
}
