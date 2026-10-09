package opensamguk.gameapi.person

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.precheck.PeopleOptionsService
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.infra.seed.WorldTopologyPin
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.PeopleFailure
import opensamguk.logic.input.Phase
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.StrategicTopologySnapshot
import opensamguk.logic.world.WorldMapVariant
import org.mockito.Mockito.*
import java.security.MessageDigest
import kotlin.test.*

class CaptiveProvinceNamesTest {
    private val reader = mock(DomesticReader::class.java)
    private val states = mock(WorldStateReadRepository::class.java)
    private val worlds = mock(ActiveWorldArtifactResolver::class.java)
    private val pins = mock(WorldArtifactIdentityReadRepository::class.java)
    private val names = ProvinceNamesCacheReader(states, worlds, pins)
    private val service = PeopleOptionsService(reader, names)
    private val phase = Phase(200, 1, 1)
    private val actor = DomesticPerson(7, "포획자", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "P1", false, emptyMap())
    private val marker = CaptiveState(actor.id, "P1", phase, "battle-8")
    private val captive = actor.copy(id = 8, name = "포로", nationId = 2, userOwned = false,
        npcState = 2, node = "P2", meta = mapOf(CaptiveState.META_KEY to marker.toMetaValue()))
    private val projection = DomesticProjection(RuleProfile.HWIHA, phase, listOf(actor, captive),
        emptyList(), emptyList(), emptyList(), setOf("P1", "P2"))
    private val world = WorldStateReadEntity(id = 7, config = mapOf("mapName" to "han-world-v3"))
    private val hash = "b".repeat(64)
    private val bytes = """{"provinceRecords":[{"id":"P1","displayName":"구금 지역"},{"id":"P2","displayName":"현재 지역"}]}""".toByteArray()

    private fun setup(source: ByteArray = bytes) {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection,
            countyNames = mapOf(11 to "잘못된 현 이름")))
        `when`(states.findProcessWorld()).thenReturn(world)
        `when`(pins.readPins(world.id)).thenReturn(listOf(WorldTopologyPin("province_control", "synthetic", hash)))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val route = mock(StrategicRouteProjection::class.java)
        val topology = mock(StrategicTopologySnapshot::class.java)
        `when`(worlds.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(bundle.variant).thenReturn(WorldMapVariant.V3_1428)
        `when`(bundle.projection).thenReturn(route)
        `when`(route.topology).thenReturn(topology)
        `when`(topology.contentHash).thenReturn(hash)
        `when`(topology.topologyRevision).thenReturn("synthetic")
        `when`(topology.landProvinceIds).thenReturn(setOf("P1", "P2"))
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        `when`(topology.artifactHashes).thenReturn(mapOf(ProvinceNamesCache.TILES_PATH to sourceHash))
        `when`(bundle.artifactBytes(ProvinceNamesCache.TILES_PATH)).thenReturn(source)
    }

    @Test fun `owned captive gets held province label without changing current position or action gates`() {
        setup()
        val result = service.captives(actor.id, 42L)
        assertTrue(result.available)
        val held = result.targets.single()
        assertEquals("구금 지역", held.heldProvinceName)
        assertEquals("P1", held.heldProvinceId)
        assertEquals("P2", held.actualProvinceId)
        assertEquals(phase, held.capturedAt)
        assertEquals("NONE", held.expiry)
        assertFalse(held.releaseAvailable)
        assertEquals(PeopleFailure.TARGET_UNAVAILABLE.name, held.releaseCode)
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(result)
        assertEquals("구금 지역", json["targets"][0]["heldProvinceName"].asText())
        assertEquals("P2", json["targets"][0]["actualProvinceId"].asText())
        assertFalse(json["targets"][0].has("actualProvinceName"))
        verify(reader).requireOwner(actor.id, 42L)
    }

    @Test fun `unknown held province is null and never borrows current location name`() {
        setup()
        val unknown = captive.copy(meta = mapOf(CaptiveState.META_KEY to marker.copy(heldProvinceId = "unknown").toMetaValue()))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection.copy(people = listOf(actor, unknown))))
        val held = service.captives(actor.id, 42L).targets.single()
        assertNull(held.heldProvinceName)
        assertEquals("unknown", held.heldProvinceId)
        val json = ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(held)
        assertTrue(json.has("heldProvinceName"))
        assertTrue(json["heldProvinceName"].isNull)
    }

    @Test fun `missing names world leaves existing captive read available`() {
        setup()
        `when`(states.findProcessWorld()).thenReturn(null)
        val result = service.captives(actor.id, 42L)
        assertTrue(result.available)
        assertNull(result.targets.single().heldProvinceName)
        verifyNoInteractions(worlds, pins)
    }

    @Test fun `warm cache cannot supply names after persisted pins are absent or changed`() {
        setup()
        val original = service.captives(actor.id, 42L)
        assertEquals("구금 지역", original.targets.single().heldProvinceName)
        for (stored in listOf(emptyList(), listOf(WorldTopologyPin("province_control", "changed", hash)),
            listOf(WorldTopologyPin("province_control", "synthetic", "c".repeat(64))))) {
            `when`(pins.readPins(world.id)).thenReturn(stored)
            assertEquals(original.copy(targets = original.targets.map { it.copy(heldProvinceName = null) }),
                service.captives(actor.id, 42L))
        }
    }

    @Test fun `mismatched world cannot supply cached labels`() {
        setup()
        assertEquals("구금 지역", service.captives(actor.id, 42L).targets.single().heldProvinceName)
        val selected = worlds.resolve()!!
        `when`(worlds.resolve()).thenReturn(selected.copy(world = WorldStateReadEntity(id = 9)))
        assertNull(service.captives(actor.id, 42L).targets.single().heldProvinceName)
    }

    @Test fun `malformed blank and incomplete label sources produce null without hiding own captive`() {
        for (source in listOf("{}".toByteArray(), bytes.toString(Charsets.UTF_8).replace("구금 지역", " ").toByteArray(),
            """{"provinceRecords":[{"id":"P2","displayName":"현재 지역"}]}""".toByteArray())) {
            setup(source)
            val result = service.captives(actor.id, 42L)
            assertTrue(result.available)
            assertEquals(captive.id, result.targets.single().generalId)
            assertNull(result.targets.single().heldProvinceName)
        }
    }

    @Test fun `foreign malformed and old captive markers never cause label lookup or disclosure`() {
        val foreign = captive.copy(meta = mapOf(CaptiveState.META_KEY to marker.copy(captorGeneralId = 99).toMetaValue()))
        val old = captive.copy(id = 9, meta = mapOf(CaptiveState.META_KEY to mapOf("captorGeneralId" to actor.id)))
        val malformed = captive.copy(id = 10, meta = mapOf(CaptiveState.META_KEY to "invalid"))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection.copy(people = listOf(actor, foreign, old, malformed))))
        assertTrue(service.captives(actor.id, 42L).targets.isEmpty())
        verifyNoInteractions(states, worlds, pins)
    }

    @Test fun `mixed captive list preserves sorting and resolves labels only once for own targets`() {
        setup()
        val second = captive.copy(id = 12, node = "P1")
        val foreign = captive.copy(id = 9, meta = mapOf(CaptiveState.META_KEY to marker.copy(captorGeneralId = 99).toMetaValue()))
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection.copy(people = listOf(second, foreign, actor, captive))))
        val targets = service.captives(actor.id, 42L).targets
        assertEquals(listOf(8, 12), targets.map { it.generalId })
        assertEquals(listOf("구금 지역", "구금 지역"), targets.map { it.heldProvinceName })
        assertTrue(targets.last().releaseAvailable)
        verify(states, times(1)).findProcessWorld()
        verify(pins, times(1)).readPins(world.id)
    }

    @Test fun `nonowner is rejected before snapshot and label reads`() {
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(actor.id, 99L)
        assertFailsWith<DomesticForbidden> { service.captives(actor.id, 99L) }
        verify(reader, never()).snapshot()
        verifyNoInteractions(states, worlds, pins)
    }

    @Test fun `missing actor and unavailable snapshot never read province labels`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection.copy(people = listOf(captive))))
        assertEquals(PeopleFailure.ACTOR_NOT_FOUND.name, service.captives(actor.id, 42L).code)
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(failure = "UNAVAILABLE"))
        assertEquals(PeopleFailure.STATE_UNAVAILABLE.name, service.captives(actor.id, 42L).code)
        verifyNoInteractions(states, worlds, pins)
    }
}
