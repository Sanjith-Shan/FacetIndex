package facetindex

import facetindex.attrs.AttributeStore
import facetindex.data.Csr
import facetindex.data.Formats
import facetindex.data.GroundTruth
import facetindex.simd.L2
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class KernelAndStoreTest {
    @Test
    fun `SIMD uint8 L2 equals the scalar loop for every dimension and value`(): Unit = runBlocking {
        checkAll(500, Arb.int(1..300)) { d ->
            val r = Random(d)
            val a = ByteArray(d) { r.nextInt(256).toByte() }
            val b = ByteArray(d) { r.nextInt(256).toByte() }
            val seg = Arena.global().allocate(d.toLong() + 7)
            MemorySegment.copy(b, 0, seg, ValueLayout.JAVA_BYTE, 7, d)
            assertEquals(L2.u8Scalar(a, seg, 7, d), L2.u8(a, seg, 7, d))
            assertEquals(L2.u8(a, b), L2.u8(a, seg, 7, d))
        }
        Unit
    }

    @Test
    fun `signed kernel on shifted vectors equals the unsigned kernel on the originals`(): Unit = runBlocking {
        checkAll(300, Arb.int(1..300)) { d ->
            val r = Random(d + 1000)
            val a = ByteArray(d) { r.nextInt(256).toByte() }
            val b = ByteArray(d) { r.nextInt(256).toByte() }
            val sb = b.copyOf().also { facetindex.data.U8Store.toLuceneBytes(it) }
            val sa = a.copyOf().also { facetindex.data.U8Store.toLuceneBytes(it) }
            val seg = Arena.global().allocate(d.toLong())
            MemorySegment.copy(sb, 0, seg, ValueLayout.JAVA_BYTE, 0, d)
            assertEquals(L2.u8(a, b), L2.i8(sa, seg, 0, d))
        }
        Unit
    }

    @Test
    fun `extreme uint8 values do not overflow`(): Unit = runBlocking {
        checkAll(200, Arb.byteArray(Arb.constant(192), Arb.byte())) { a ->
            val b = ByteArray(192) { (a[it].toInt() xor 0xFF).toByte() }
            val seg = Arena.global().allocate(192)
            MemorySegment.copy(b, 0, seg, ValueLayout.JAVA_BYTE, 0, 192)
            assertEquals(L2.u8(a, b), L2.u8(a, seg, 0, 192))
        }
        Unit
    }

    @Test
    fun `file formats round trip`() {
        val dir = Files.createTempDirectory("fmt")
        val csr = Csr.of(listOf(intArrayOf(1, 5), intArrayOf(), intArrayOf(0, 2, 9)), 10)
        Formats.writeSpmat(dir.resolve("m.spmat"), csr)
        val back = Formats.readSpmat(dir.resolve("m.spmat"))
        assertEquals(3, back.nrow); assertEquals(10, back.ncol)
        assertArrayEquals(csr.offsets, back.offsets); assertArrayEquals(csr.indices, back.indices)
        val gt = GroundTruth(2, 3, intArrayOf(1, 2, 3, 4, -1, -1), floatArrayOf(1f, 2f, 3f, 1f, Float.MAX_VALUE, Float.MAX_VALUE))
        Formats.writeIbin(dir.resolve("g.ibin"), gt)
        val g2 = Formats.readIbin(dir.resolve("g.ibin"))
        assertArrayEquals(gt.ids, g2.ids); assertEquals(1, g2.nValid(1))
        Formats.writeU8bin(dir.resolve("v.u8bin"), 2, 4) { i -> ByteArray(4) { (i * 10 + it).toByte() } }
        val m = Formats.readU8Matrix(dir.resolve("v.u8bin"))
        assertArrayEquals(byteArrayOf(10, 11, 12, 13), m.row(1))
    }

    @Test
    fun `recall counts ties the benchmark's way`() {
        // GT with k = 12, entries 10 and 11 tie with the 10th distance: a result holding them counts.
        val ids = IntArray(12) { it }
        val d = FloatArray(12) { if (it >= 9) 9f else it.toFloat() }
        val gt = GroundTruth(1, 12, ids, d)
        assertEquals(10, gt.hits(0, intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 11), 10))
    }

    @Test
    fun `attribute store matches a naive model under random operations`() {
        val r = Random(3)
        val nTags = 40
        val n = 500
        val model = HashMap<Int, MutableSet<Int>>()
        val rows = List(n) { IntArray(r.nextInt(0, 5)) { r.nextInt(nTags) }.distinct().sorted().toIntArray() }
        val store = AttributeStore(nTags, n)
        store.load(Csr.of(rows, nTags))
        rows.forEachIndexed { i, t -> model[i] = t.toMutableSet() }
        repeat(5000) {
            val row = r.nextInt(n + 100)
            when (r.nextInt(4)) {
                0 -> { val t = IntArray(r.nextInt(0, 8)) { r.nextInt(nTags) }; store.insert(row, t); model[row] = t.toMutableSet() }
                1 -> { val ok = store.delete(row); assertEquals(model.remove(row) != null, ok) }
                else -> {
                    val add = IntArray(r.nextInt(0, 4)) { r.nextInt(nTags) }
                    val rem = IntArray(r.nextInt(0, 3)) { r.nextInt(nTags) }
                    val ok = store.setAttrs(row, add, rem)
                    assertEquals(row in model, ok)
                    model[row]?.let { s -> s.addAll(add.toList()); s.removeAll(rem.filter { it !in add }.toSet()) }
                }
            }
        }
        for (row in 0 until n + 100) {
            assertEquals(row in model, store.isLive(row))
            if (row in model) assertEquals(model[row]!!.sorted(), store.tagsOf(row).toList())
        }
        for (t in 0 until nTags) {
            val exp = model.filterValues { t in it }.keys.sorted()
            assertEquals(exp.size, store.cardinality(t))
            assertEquals(exp, store.matching(intArrayOf(t)).toArray().toList())
        }
        for (t1 in 0 until 10) for (t2 in 10 until 20) {
            val exp = model.count { t1 in it.value && t2 in it.value }
            assertEquals(exp, store.matchCount(intArrayOf(t1, t2)))
        }
    }

    @Test
    fun `readers never see a torn row while one writer rewrites it`() {
        val store = AttributeStore(100, 4)
        store.load(Csr.of(listOf(intArrayOf(0, 1, 2), intArrayOf(5), intArrayOf(), intArrayOf(7)), 100))
        // Invariant: row 0 always holds a contiguous run [x, x + len) of tags.
        val stop = AtomicBoolean(false)
        val bad = AtomicLong()
        val readers = List(3) {
            Thread {
                while (!stop.get()) {
                    val t = store.tagsOf(0)
                    for (i in 1 until t.size) if (t[i] != t[0] + i) bad.incrementAndGet()
                }
            }.apply { start() }
        }
        val r = Random(1)
        repeat(20_000) {
            val x = r.nextInt(0, 50)
            val len = r.nextInt(1, 12)
            val cur = store.tagsOf(0)
            store.setAttrs(0, IntArray(len) { x + it }, cur)
        }
        stop.set(true)
        readers.forEach { it.join() }
        assertEquals(0L, bad.get())
        assertTrue(store.isLive(0)); assertFalse(store.isLive(9))
    }
}
