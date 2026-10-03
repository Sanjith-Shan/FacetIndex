package facetindex.util

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.TimeUnit

object Json {
    /** Snake-case JSON everywhere. */
    val mapper: ObjectMapper = jacksonObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun readLines(path: Path): Sequence<JsonNode> = sequence {
        Files.newBufferedReader(path).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isNotBlank()) yield(mapper.readTree(line))
            }
        }
    }

    fun write(v: Any?): String = mapper.writeValueAsString(v)
}

/** Appends JSON lines to a results file (creating parent directories). */
class JsonlWriter(path: Path) : AutoCloseable {
    private val w: BufferedWriter

    init {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        w = Files.newBufferedWriter(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @Synchronized
    fun write(row: Any) {
        w.write(Json.write(row)); w.newLine(); w.flush()
    }

    override fun close() = w.close()
}

/** Machine and load descriptors that every results line carries. */
object Machine {
    private fun sh(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) out else null
    } catch (_: Exception) {
        null
    }

    private val windows = System.getProperty("os.name").lowercase().contains("windows")
    private val osBean get() = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    private fun ps(cmd: String): String? = sh("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd)

    /** A short label for the box, from FACETINDEX_MACHINE if set (for example "minipc"). */
    val label: String = System.getenv("FACETINDEX_MACHINE") ?: if (windows) "minipc" else "dev"

    val info: Map<String, Any?> by lazy {
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        val cpu = when {
            mac -> sh("sysctl", "-n", "machdep.cpu.brand_string")
            windows -> ps("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name")
            else -> sh("sh", "-c", "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2")?.trim()
        }
        mapOf(
            "label" to label,
            "cpu" to cpu,
            "cores" to Runtime.getRuntime().availableProcessors(),
            "ram_bytes" to osBean?.totalMemorySize,
            "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}",
            "java" to System.getProperty("java.version"),
            "max_heap_bytes" to Runtime.getRuntime().maxMemory(),
            "lucene" to org.apache.lucene.util.Version.LATEST.toString(),
        )
    }

    /**
     * Windows has no load average (the MXBean returns -1), so whole-machine CPU use is sampled over
     * [sampleMs] and recorded with free physical memory.
     */
    fun load(sampleMs: Long = 1000): Map<String, Any?> {
        val bean = osBean
        bean?.cpuLoad
        if (sampleMs > 0) Thread.sleep(sampleMs)
        return mapOf(
            "loadavg_1m" to ManagementFactory.getOperatingSystemMXBean().systemLoadAverage,
            "system_cpu_load" to bean?.cpuLoad,
            "process_cpu_load" to bean?.processCpuLoad,
            "free_ram_bytes" to bean?.freeMemorySize,
            "at" to Instant.now().toString(),
        )
    }

    /** Resident set size of this process in bytes (the working set on Windows). */
    fun rssBytes(): Long? {
        val pid = ProcessHandle.current().pid().toString()
        return if (windows) ps("(Get-Process -Id $pid).WorkingSet64")?.trim()?.toLongOrNull()
        else sh("ps", "-o", "rss=", "-p", pid)?.trim()?.toLongOrNull()?.times(1024)
    }

    /** Peak working set on Windows, VmHWM elsewhere. */
    fun peakRssBytes(): Long? {
        val pid = ProcessHandle.current().pid().toString()
        return if (windows) ps("(Get-Process -Id $pid).PeakWorkingSet64")?.trim()?.toLongOrNull()
        else runCatching {
            Files.readAllLines(Paths.get("/proc/self/status")).firstOrNull { it.startsWith("VmHWM") }
                ?.split(Regex("\\s+"))?.get(1)?.toLong()?.times(1024)
        }.getOrNull()
    }

    fun heapUsedAfterGc(): Long {
        repeat(2) { System.gc(); Thread.sleep(100) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }
}

/** `--key value` and bare `--flag` arguments. */
class Args(argv: List<String>) {
    private val values = HashMap<String, String>()

    init {
        var i = 0
        while (i < argv.size) {
            val a = argv[i]
            require(a.startsWith("--")) { "unexpected argument '$a'" }
            val eq = a.indexOf('=')
            if (eq > 0) {
                values[a.substring(2, eq)] = a.substring(eq + 1); i++
            } else if (i + 1 < argv.size && !argv[i + 1].startsWith("--")) {
                values[a.substring(2)] = argv[i + 1]; i += 2
            } else {
                values[a.substring(2)] = "true"; i++
            }
        }
    }

    fun has(k: String) = k in values
    fun str(k: String): String? = values[k]
    fun str(k: String, def: String): String = values[k] ?: def
    fun req(k: String): String = values[k] ?: throw IllegalArgumentException("--$k is required")
    fun path(k: String): Path = Paths.get(req(k))
    fun path(k: String, def: Path): Path = values[k]?.let { Paths.get(it) } ?: def
    fun pathOrNull(k: String): Path? = values[k]?.let { Paths.get(it) }
    fun int(k: String, def: Int) = values[k]?.toInt() ?: def
    fun long(k: String, def: Long) = values[k]?.toLong() ?: def
    fun double(k: String, def: Double) = values[k]?.toDouble() ?: def
    fun flag(k: String, def: Boolean = false) = values[k]?.toBooleanStrict() ?: def
    fun list(k: String, def: String): List<String> = str(k, def).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun ints(k: String, def: String): List<Int> = list(k, def).map { it.toInt() }
    fun doubles(k: String, def: String): List<Double> = list(k, def).map { it.toDouble() }
}

/** Where large data lives: \$FACETINDEX_DATA, never inside the repo. */
object DataDir {
    val root: Path = Paths.get(System.getenv("FACETINDEX_DATA") ?: "C:/SullaPortal/data/facetindex")
    fun resolve(rel: String): Path = root.resolve(rel)
}

fun dirBytes(dir: Path): Long =
    if (!Files.exists(dir)) 0 else Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { f -> runCatching { Files.size(f) }.getOrDefault(0) }.sum() }

fun deleteRecursively(dir: Path) {
    if (!Files.exists(dir)) return
    Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
}

fun copyRecursively(src: Path, dst: Path) {
    Files.walk(src).use { s ->
        s.forEach { p ->
            val t = dst.resolve(src.relativize(p).toString())
            if (Files.isDirectory(p)) Files.createDirectories(t) else Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
