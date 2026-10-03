package facetindex.api

import facetindex.api.v1.Ack
import facetindex.api.v1.CatalogEvent
import facetindex.api.v1.DeleteRequest
import facetindex.api.v1.FacetIndexGrpcKt
import facetindex.api.v1.Hit
import facetindex.api.v1.SearchRequest
import facetindex.api.v1.SearchResponse
import facetindex.api.v1.SetAttrsRequest
import facetindex.api.v1.StatsRequest
import facetindex.api.v1.StatsResponse
import facetindex.api.v1.UpsertRequest
import facetindex.data.QueryVector
import facetindex.planner.Planner
import facetindex.stream.Applier
import facetindex.strategy.Engine
import facetindex.strategy.FilterStrategy
import facetindex.strategy.Predicate
import facetindex.strategy.RangeClause
import facetindex.strategy.SearchBudget
import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The gRPC service: Search (planner or a named strategy), Upsert, Delete, SetAttrs, Stats. Writes go
 * through the same [Applier] as the Kafka consumer, under one lock so the single-writer structures
 * see one writer, and each write gets the next sequence number as its version.
 */
class FacetIndexService(
    private val engine: Engine,
    private val strategies: Map<String, Pair<FilterStrategy, SearchBudget>>,
    private val planner: Planner?,
    private val applier: Applier?,
    private val stats: () -> Map<String, Long> = { emptyMap() },
) : FacetIndexGrpcKt.FacetIndexCoroutineImplBase() {
    private val seq = AtomicLong(System.currentTimeMillis() * 1000)
    private val writeLock = Any()

    /** Turns unexpected exceptions into INTERNAL with the cause, instead of gRPC's bare UNKNOWN. */
    private inline fun <T> guarded(body: () -> T): T = try {
        body()
    } catch (e: StatusException) {
        throw e
    } catch (e: Exception) {
        throw StatusException(Status.INTERNAL.withDescription(e.toString()).withCause(e))
    }

    override suspend fun search(request: SearchRequest): SearchResponse = guarded { doSearch(request) }

    private fun doSearch(request: SearchRequest): SearchResponse {
        val t0 = System.nanoTime()
        val q: QueryVector = when {
            !request.vectorU8.isEmpty -> QueryVector.U8(request.vectorU8.toByteArray())
            request.vectorF32Count > 0 -> QueryVector.F32(request.vectorF32List.toFloatArray())
            else -> throw StatusException(Status.INVALID_ARGUMENT.withDescription("vector required"))
        }
        val p = Predicate(request.predicate.tagsList.toIntArray(), request.predicate.rangesList.map { RangeClause(it.attr, it.lo, it.hi) })
        val k = if (request.k > 0) request.k else 10
        val st = engine.stats(p)
        val (name, pair) = when {
            request.mode.isNotEmpty() -> request.mode to (strategies[request.mode] ?: throw StatusException(Status.INVALID_ARGUMENT.withDescription("unknown mode ${request.mode}")))
            planner != null -> planner.choose(st).config.let { it to strategies.getValue(it) }
            else -> strategies.entries.first().let { it.key to it.value }
        }
        val budget = pair.second.let { b -> b.copy(ef = if (request.ef > 0) request.ef else b.ef, nprobe = if (request.nprobe > 0) request.nprobe else b.nprobe) }
        val r = pair.first.search(q, p, k, budget)
        return SearchResponse.newBuilder()
            .addAllHits(r.rows.indices.map { Hit.newBuilder().setId(r.rows[it]).setDistance(r.dists[it]).build() })
            .setStrategy(name).setMatches(st.matches).setSelectivity(st.selectivity)
            .setTookMicros((System.nanoTime() - t0) / 1000).build()
    }

    private fun write(build: (Long) -> CatalogEvent): Ack = guarded { doWrite(build) }

    private fun doWrite(build: (Long) -> CatalogEvent): Ack {
        val a = applier ?: throw StatusException(Status.FAILED_PRECONDITION.withDescription("read-only server"))
        synchronized(writeLock) {
            val s = seq.incrementAndGet()
            val before = a.applied.get()
            a.apply(build(s))
            return Ack.newBuilder().setApplied(a.applied.get() > before).setSequence(s).build()
        }
    }

    override suspend fun upsert(request: UpsertRequest): Ack = write { s ->
        CatalogEvent.newBuilder().setInsert(request.toBuilder().setVersion(s)).setEventSeq(s).setProducedAtMicros(facetindex.stream.Events.nowMicros()).build()
    }

    override suspend fun delete(request: DeleteRequest): Ack = write { s ->
        CatalogEvent.newBuilder().setDelete(request.toBuilder().setVersion(s)).setEventSeq(s).setProducedAtMicros(facetindex.stream.Events.nowMicros()).build()
    }

    override suspend fun setAttrs(request: SetAttrsRequest): Ack = write { s ->
        CatalogEvent.newBuilder().setSetAttrs(request.toBuilder().setVersion(s)).setEventSeq(s).setProducedAtMicros(facetindex.stream.Events.nowMicros()).build()
    }

    override suspend fun stats(request: StatsRequest): StatsResponse = StatsResponse.newBuilder()
        .setLiveItems(engine.attrs.liveCount().toLong())
        .putAllCounters(stats() + (applier?.counters() ?: emptyMap()))
        .build()

    companion object {
        fun start(service: FacetIndexService, port: Int, threads: Int = Runtime.getRuntime().availableProcessors()): Server =
            NettyServerBuilder.forPort(port).addService(service).executor(Executors.newFixedThreadPool(threads)).build().start()
    }
}
