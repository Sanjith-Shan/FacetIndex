package facetindex

import com.google.protobuf.ByteString
import facetindex.api.FacetIndexService
import facetindex.api.v1.FacetIndexGrpcKt
import facetindex.api.v1.SearchRequest
import facetindex.api.v1.SetAttrsRequest
import facetindex.api.v1.StatsRequest
import facetindex.api.v1.UpsertRequest
import facetindex.api.v1.Predicate as PbPredicate
import facetindex.stream.Applier
import facetindex.stream.AttrMode
import facetindex.strategy.LuceneFilteredHnsw
import facetindex.strategy.PreFilterBruteForce
import facetindex.strategy.SearchBudget
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GrpcServiceTest {
    @Test
    fun `search, upsert and set-attrs over gRPC`(): Unit = runBlocking {
        Fixture(n = 1500).use { fx ->
            val e = fx.engine()
            val applier = Applier(fx.index, fx.attrs, fx.ivf, AttrMode.A3, overlay = fx.overlay).also { it.seed(0 until fx.n) }
            val svc = FacetIndexService(e, mapOf("S0" to (PreFilterBruteForce(e) to SearchBudget()), "S2" to (LuceneFilteredHnsw(e, "S2", 0) to SearchBudget(ef = 50))), null, applier)
            val server = FacetIndexService.start(svc, 0, 2)
            val ch = ManagedChannelBuilder.forAddress("127.0.0.1", server.port).usePlaintext().build()
            try {
                val stub = FacetIndexGrpcKt.FacetIndexCoroutineStub(ch)
                val q = fx.randomQuery()
                val tags = fx.rowsTags[3].take(1).toIntArray()
                val resp = stub.search(SearchRequest.newBuilder().setVectorU8(ByteString.copyFrom(q)).setK(10).setMode("S0")
                    .setPredicate(PbPredicate.newBuilder().addAllTags(tags.asList())).build())
                assertEquals(fx.bruteForce(q, tags, 10), resp.hitsList.map { it.id })
                assertEquals("S0", resp.strategy)
                // A new item that is the query itself, with a fresh tag, must come back first.
                stub.upsert(UpsertRequest.newBuilder().setId(fx.n + 5).setVectorU8(ByteString.copyFrom(q)).addTags(59).build())
                val r2 = stub.search(SearchRequest.newBuilder().setVectorU8(ByteString.copyFrom(q)).setK(3).setMode("S0")
                    .setPredicate(PbPredicate.newBuilder().addTags(59)).build())
                assertEquals(fx.n + 5, r2.hitsList.first().id)
                stub.setAttrs(SetAttrsRequest.newBuilder().setId(fx.n + 5).addRemove(59).build())
                val r3 = stub.search(SearchRequest.newBuilder().setVectorU8(ByteString.copyFrom(q)).setK(3).setMode("S0")
                    .setPredicate(PbPredicate.newBuilder().addTags(59)).build())
                assertTrue(r3.hitsList.none { it.id == fx.n + 5 })
                assertEquals(fx.n + 1L, stub.stats(StatsRequest.getDefaultInstance()).liveItems)
            } finally {
                ch.shutdownNow(); server.shutdownNow()
            }
        }
    }
}
