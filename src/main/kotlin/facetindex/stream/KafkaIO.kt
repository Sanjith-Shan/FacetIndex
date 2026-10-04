package facetindex.stream

import facetindex.api.v1.CatalogEvent
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.IntegerDeserializer
import org.apache.kafka.common.serialization.IntegerSerializer
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** Where events come from: Kafka, or the in-process twin that calls the applier directly. */
interface EventSink : AutoCloseable {
    fun send(e: CatalogEvent)
    fun flush() {}
}

object Topics {
    fun recreate(bootstrap: String, topic: String, partitions: Int) {
        Admin.create(Properties().apply { put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap) }).use { admin ->
            if (topic in admin.listTopics().names().get(30, TimeUnit.SECONDS)) {
                admin.deleteTopics(listOf(topic)).all().get(60, TimeUnit.SECONDS)
                // Deletion is asynchronous; wait until the name is gone.
                repeat(100) { if (topic !in admin.listTopics().names().get()) return@repeat; Thread.sleep(200) }
            }
            var created = false
            repeat(50) {
                if (created) return@repeat
                try {
                    admin.createTopics(listOf(NewTopic(topic, partitions, 1.toShort()))).all().get(60, TimeUnit.SECONDS); created = true
                } catch (e: Exception) {
                    if (e.cause !is org.apache.kafka.common.errors.TopicExistsException) throw e
                    Thread.sleep(500)
                }
            }
        }
    }
}

/** Kafka producer of catalog events, keyed by item id so one item's events stay in one partition. */
class KafkaSink(bootstrap: String, private val topic: String) : EventSink {
    private val producer = KafkaProducer<Int, ByteArray>(Properties().apply {
        put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer::class.java)
        put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer::class.java)
        put(ProducerConfig.ACKS_CONFIG, "all")
        put(ProducerConfig.LINGER_MS_CONFIG, "2")
        put(ProducerConfig.BATCH_SIZE_CONFIG, (256 * 1024).toString())
        put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
    })
    val sent = AtomicLong()
    val errors = AtomicLong()

    override fun send(e: CatalogEvent) {
        producer.send(ProducerRecord(topic, Events.idOf(e), e.toByteArray())) { _, ex -> if (ex != null) errors.incrementAndGet() }
        sent.incrementAndGet()
    }

    override fun flush() = producer.flush()
    override fun close() = producer.close(Duration.ofSeconds(30))
}

/** The in-process twin: hands each event straight to the applier on the caller's thread. */
class DirectSink(private val applier: Applier) : EventSink {
    override fun send(e: CatalogEvent) = applier.apply(e)
    override fun close() {}
}

/**
 * The one consumer: polls, applies each record in partition order, and commits offsets only after
 * the whole poll batch is applied (at-least-once; the applier's version check makes replays
 * harmless). [crashAfter] makes it throw after applying that many events of a batch, before the
 * commit, which is how the crash-replay test simulates a consumer dying mid-batch.
 */
class KafkaCatalogConsumer(
    bootstrap: String,
    topic: String,
    group: String,
    private val applier: Applier,
    private val applyThreads: Int = 1,
    private val crashAfter: Long = -1,
    maxPollRecords: Int = 2000,
) : AutoCloseable {
    private val consumer = KafkaConsumer<Int, ByteArray>(Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(ConsumerConfig.GROUP_ID_CONFIG, group)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, IntegerDeserializer::class.java)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords.toString())
        put(ConsumerConfig.FETCH_MAX_BYTES_CONFIG, (64 * 1024 * 1024).toString())
        put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, (16 * 1024 * 1024).toString())
        put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "900000")
    }).also { it.subscribe(listOf(topic)) }

    @Volatile
    var running = true

    /** True once the consumer has been assigned partitions (the group join is done). */
    @Volatile
    var ready = false
    val consumed = AtomicLong()
    var error: Throwable? = null
        private set
    private var thread: Thread? = null
    private val pool = if (applyThreads > 1) java.util.concurrent.Executors.newFixedThreadPool(applyThreads) else null

    fun start(): KafkaCatalogConsumer {
        thread = Thread({ loop() }, "catalog-consumer").apply { isDaemon = true; start() }
        return this
    }

    private fun loop() {
        try {
            var sinceStart = 0L
            while (running) {
                val records = consumer.poll(Duration.ofMillis(100))
                if (!ready && consumer.assignment().isNotEmpty()) ready = true
                if (records.isEmpty) continue
                val events = ArrayList<CatalogEvent>(records.count())
                for (r in records) events += CatalogEvent.parseFrom(r.value())
                if (crashAfter >= 0) {
                    for (e in events) {
                        if (sinceStart == crashAfter) throw SimulatedCrash()
                        applier.apply(e); sinceStart++
                    }
                } else applier.applyBatch(events, applyThreads, pool)
                consumed.addAndGet(events.size.toLong())
                consumer.commitSync()
            }
        } catch (_: SimulatedCrash) {
            error = SimulatedCrash()
        } catch (_: org.apache.kafka.common.errors.WakeupException) {
        } catch (t: Throwable) {
            error = t
        } finally {
            runCatching { consumer.close(Duration.ofSeconds(10)) }
            pool?.shutdownNow()
        }
    }

    fun join(ms: Long) = thread?.join(ms)

    override fun close() {
        running = false
        consumer.wakeup()
        thread?.join(15_000)
    }

    class SimulatedCrash : RuntimeException("simulated consumer crash")
}

/**
 * Open-loop pacing: event i is due at start + i / rate whatever happened to earlier events, so a
 * slow sink shows up as backlog instead of a silently stretched schedule (ported from an earlier
 * project's update replayer).
 */
class Pacer(private val rate: Double) {
    private val start = System.nanoTime()
    private var i = 0L

    /** Blocks until the next event is due. */
    fun awaitNext() {
        val due = start + (i * 1e9 / rate).toLong()
        i++
        while (true) {
            val wait = due - System.nanoTime()
            if (wait <= 0) return
            LockSupport.parkNanos(minOf(wait, 1_000_000))
        }
    }

    /** Events that were due by now. */
    fun intended(): Long = ((System.nanoTime() - start) * rate / 1e9).toLong()
}
