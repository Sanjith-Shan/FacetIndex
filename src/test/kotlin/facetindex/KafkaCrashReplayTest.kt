package facetindex

import facetindex.api.v1.CatalogEvent
import facetindex.attrs.AttributeStore
import facetindex.data.Csr
import facetindex.index.IndexSchema
import facetindex.index.VectorIndex
import facetindex.stream.Applier
import facetindex.stream.AttrMode
import facetindex.stream.Events
import facetindex.stream.KafkaCatalogConsumer
import facetindex.stream.KafkaSink
import facetindex.stream.Topics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.kafka.KafkaContainer
import java.nio.file.Files
import kotlin.random.Random

/**
 * Kills the consumer in the middle of a poll batch (after it applied some events but before it
 * committed offsets), starts a new one in the same group, and checks that the final state equals
 * applying every event exactly once. Runs against $FACETINDEX_KAFKA if set (the mini PC's broker),
 * otherwise a Testcontainers broker (CI).
 */
@Tag("docker")
class KafkaCrashReplayTest {
    @Test
    fun `a consumer crash mid-batch replays to the same state`() {
        val external = System.getenv("FACETINDEX_KAFKA")
        val container = if (external == null) KafkaContainer("apache/kafka:4.1.0").also { it.start() } else null
        val bootstrap = external ?: container!!.bootstrapServers
        try {
            run(bootstrap)
        } finally {
            container?.stop()
        }
    }

    private fun run(bootstrap: String) {
        val topic = "crash-test-" + System.nanoTime()
        Topics.recreate(bootstrap, topic, 4)
        val r = Random(9)
        val nTags = 30
        val base = List(200) { IntArray(r.nextInt(1, 4)) { r.nextInt(nTags) }.distinct().sorted().toIntArray() }
        // The event stream: inserts of new ids, deletes and attribute flips, in sequence order.
        val events = ArrayList<CatalogEvent>()
        var seq = 1L
        var nextId = 200
        repeat(3000) {
            events += when (r.nextInt(3)) {
                0 -> Events.insert(seq++, nextId++, ByteArray(8) { r.nextInt(256).toByte() }, null, IntArray(2) { r.nextInt(nTags) })
                1 -> Events.delete(seq++, r.nextInt(nextId))
                else -> Events.setAttrs(seq++, r.nextInt(nextId), intArrayOf(r.nextInt(nTags)), intArrayOf(r.nextInt(nTags)))
            }
        }
        fun freshState(): Pair<AttributeStore, VectorIndex> {
            val a = AttributeStore(nTags, 200).also { it.load(Csr.of(base, nTags)) }
            val idx = VectorIndex(Files.createTempDirectory("crash"), IndexSchema(), create = true, refreshMs = 0)
            return a to idx
        }
        // Expected: every event applied once, in order.
        val (expAttrs, expIdx) = freshState()
        Applier(expIdx, expAttrs, null, AttrMode.A3).also { it.seed(0 until 200) }.let { ap -> events.forEach { ap.apply(it) } }

        val (attrs, idx) = freshState()
        val applier = Applier(idx, attrs, null, AttrMode.A3).also { it.seed(0 until 200) }
        KafkaSink(bootstrap, topic).use { s -> events.forEach { s.send(it) }; s.flush() }
        val group = "g-$topic"
        val first = KafkaCatalogConsumer(bootstrap, topic, group, applier, crashAfter = 1234, maxPollRecords = 500).start()
        first.join(60_000)
        assertTrue(first.error is KafkaCatalogConsumer.SimulatedCrash, "first consumer should have crashed: ${first.error}")
        val appliedBeforeCrash = applier.applied.get()
        val second = KafkaCatalogConsumer(bootstrap, topic, group, applier, maxPollRecords = 500).start()
        val deadline = System.currentTimeMillis() + 60_000
        while (applier.applied.get() + applier.skipped.get() < events.size + 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
        // Let the second consumer drain whatever was re-delivered.
        Thread.sleep(1000)
        second.close()
        assertTrue(applier.skipped.get() > 0, "the replay should have skipped already-applied events")
        assertTrue(appliedBeforeCrash in 1..events.size)
        for (id in 0 until nextId) {
            assertEquals(expAttrs.isLive(id), attrs.isLive(id), "liveness of $id")
            if (expAttrs.isLive(id)) assertEquals(expAttrs.tagsOf(id).toList(), attrs.tagsOf(id).toList(), "tags of $id")
        }
        idx.refreshNow(); expIdx.refreshNow()
        assertEquals(expIdx.withSearcher { it.indexReader.numDocs() }, idx.withSearcher { it.indexReader.numDocs() })
        idx.closeAndDelete(); expIdx.closeAndDelete()
    }
}
