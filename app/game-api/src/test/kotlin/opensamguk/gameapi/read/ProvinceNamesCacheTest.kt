package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.WorldMapVariant
import org.mockito.Mockito.*
import java.security.MessageDigest
import kotlin.test.*

class ProvinceNamesCacheTest {
    private val mapper = ObjectMapper()
    private val raw = """{"owner":[99],"geometry":{"private":"not a label"},"provinceRecords":[
        {"id":"P2","displayName":"둘째","nationId":99,"fog":"HIDDEN","generalId":41},
        {"id":"P1","displayName":"첫째","geometry":[1,2],"location":{"col":3}}
        ]}""".toByteArray()

    private fun artifacts(bytes: ByteArray = raw, ids: Set<String> = setOf("P1", "P2"),
                          source: String = sha(bytes), variant: WorldMapVariant = WorldMapVariant.V3_1428): ResolvedWorldArtifacts {
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        val topology = mock(StrategicTopologySnapshot::class.java)
        `when`(bundle.variant).thenReturn(variant)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.topology).thenReturn(topology)
        `when`(topology.artifactHashes).thenReturn(mapOf(ProvinceNamesCache.TILES_PATH to source))
        `when`(topology.landProvinceIds).thenReturn(ids)
        `when`(topology.topologyRevision).thenReturn("synthetic-topology")
        `when`(topology.contentHash).thenReturn("b".repeat(64))
        `when`(bundle.artifactBytes(ProvinceNamesCache.TILES_PATH)).thenReturn(bytes)
        return bundle
    }

    @Test fun `small response contains only selected stable names and public fingerprints`() {
        val response = ProvinceNamesCache(mapper).get(7, artifacts())
        assertEquals(listOf("P1", "P2"), response.dto.names.map { it.provinceId })
        val body = mapper.readTree(response.body())
        assertEquals(setOf("worldId", "mapRelease", "topologyRevision", "topologyHash", "sourceSha256", "names"),
            body.fieldNames().asSequence().toSet())
        assertTrue(body["names"].all { it.fieldNames().asSequence().toSet() == setOf("provinceId", "displayName") })
        assertEquals("\"sha256-${sha(response.body())}\"", response.etag)
        assertEquals("첫째", body["names"][0]["displayName"].asText())
    }

    @Test fun `cache hit avoids terrain byte copy parse and hash while callers cannot mutate cached bytes`() {
        val bundle = artifacts()
        val cache = ProvinceNamesCache(mapper)
        val first = cache.get(7, bundle)
        first.body().fill(0)
        val second = cache.get(7, bundle)
        assertSame(first, second)
        assertEquals(2, mapper.readTree(second.body())["names"].size())
        verify(bundle, times(1)).artifactBytes(ProvinceNamesCache.TILES_PATH)
    }

    @Test fun `world release or source change cannot retain an old response tag`() {
        val cache = ProvinceNamesCache(mapper)
        val first = cache.get(7, artifacts())
        val newWorld = cache.get(8, artifacts())
        val newRelease = cache.get(7, artifacts(variant = WorldMapVariant.V3_1447_MAP4))
        val changed = cache.get(7, artifacts(bytes = String(raw).replace("첫째", "새이름").toByteArray()))
        assertNotEquals(first.etag, newWorld.etag); assertNotEquals(first.etag, newRelease.etag)
        assertNotEquals(first.etag, changed.etag)
    }

    @Test fun `missing duplicate malformed or mismatched label sources fail closed and never populate cache`() {
        val cache = ProvinceNamesCache(mapper)
        val badRows = listOf("""{"provinceRecords":[{"id":"P1","displayName":"첫째"},{"id":"P1","displayName":"둘째"}]}""",
            """{"provinceRecords":[{"id":"P1","displayName":""}]}""",
            """{"provinceRecords":[{"id":"P1","displayName":7}]}""",
            """{"provinceRecords":[]} {"trailing":true}""", """{"geometry":[]} """)
        for (bad in badRows) assertFails { cache.get(7, artifacts(bad.toByteArray())) }
        assertFailsWith<IllegalArgumentException> { cache.get(7, artifacts(source = "c".repeat(64))) }
        assertFailsWith<IllegalArgumentException> { cache.get(7, artifacts(ids = setOf("P1", "P3"))) }
        assertEquals(2, cache.get(7, artifacts()).dto.names.size)
    }

    @Test fun `bounded LRU evicts old world entries instead of retaining unlimited snapshots`() {
        val cache = ProvinceNamesCache(mapper)
        val bundle = artifacts()
        cache.get(1, bundle)
        for (world in 2..5) cache.get(world, bundle)
        cache.get(1, bundle)
        verify(bundle, times(6)).artifactBytes(ProvinceNamesCache.TILES_PATH)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
