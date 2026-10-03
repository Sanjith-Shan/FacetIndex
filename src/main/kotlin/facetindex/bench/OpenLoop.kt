package facetindex.bench

import org.HdrHistogram.ConcurrentHistogram
import org.HdrHistogram.Histogram
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * How long before each due time the generator stops parking and spins. Windows' default timer tick
 * is about 15.6 ms, so a park can overshoot by that much there; elsewhere 2 ms is plenty.
 */
private val SPIN_NS = if (System.getProperty("os.name").startsWith("Windows")) 16_000_000L else 2_000_000L

/**
 * Open-loop arrivals at a constant rate (ported from an earlier project). Arrival i is due at
 * start + i / rate; it is handed to a worker pool at that time regardless of how earlier arrivals are
 * doing, and its latency is measured from the due time, so queueing behind slow requests counts
 * (no coordinated omission).
 */
class OpenLoop(private val rate: Double, private val threads: Int) {
    val latencyUs = ConcurrentHistogram(3)
    val dispatchLagUs = ConcurrentHistogram(3)
    val errors = AtomicLong()
    val completed = AtomicLong()
    var issued = 0L
        private set
    @Volatile
    var lastError: Throwable? = null

    @Volatile
    private var stopFlag = false
    fun stop() { stopFlag = true }

    /** Runs for [durationS] seconds after [warmupS] of unrecorded warm-up. [task] gets (arrival index, recorded). */
    fun run(durationS: Double, warmupS: Double = 0.0, onWarmupEnd: () -> Unit = {}, task: (Long, Boolean) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "load").apply { isDaemon = true } } as ThreadPoolExecutor
        val interval = 1e9 / rate
        val start = System.nanoTime()
        val warmEnd = start + (warmupS * 1e9).toLong()
        val end = warmEnd + (durationS * 1e9).toLong()
        var warmed = warmupS <= 0
        if (warmed) onWarmupEnd()
        var i = 0L
        while (!stopFlag) {
            val due = start + (i * interval).toLong()
            if (due >= end) break
            while (true) {
                val wait = due - System.nanoTime()
                if (wait <= 0) break
                if (wait > SPIN_NS) LockSupport.parkNanos(wait - SPIN_NS) else Thread.onSpinWait()
            }
            val record = due >= warmEnd
            if (record) dispatchLagUs.recordValue(((System.nanoTime() - due) / 1000).coerceAtLeast(0))
            if (record && !warmed) { warmed = true; onWarmupEnd() }
            val idx = i
            if (record) issued++
            pool.execute {
                try {
                    task(idx, record)
                    if (record) { latencyUs.recordValue(((System.nanoTime() - due) / 1000).coerceAtLeast(0)); completed.incrementAndGet() }
                } catch (t: Throwable) {
                    if (record) errors.incrementAndGet()
                    lastError = t
                }
            }
            i++
        }
        pool.shutdown()
        if (!pool.awaitTermination(120, TimeUnit.SECONDS)) pool.shutdownNow()
    }
}

fun Histogram.summary(): Map<String, Any?> = if (totalCount == 0L) mapOf("count" to 0) else linkedMapOf(
    "count" to totalCount, "mean" to mean, "p50" to getValueAtPercentile(50.0), "p90" to getValueAtPercentile(90.0),
    "p99" to getValueAtPercentile(99.0), "p999" to getValueAtPercentile(99.9), "max" to maxValue,
)
