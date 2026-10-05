package opensamguk.gameapi.creation

import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.ActiveWorldArtifactSnapshot
import opensamguk.gameapi.read.CityGeography
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.SpatialStateReadRepository
import opensamguk.gameapi.read.SpatialStateReadSnapshot
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.world.StrategicTopologySnapshot
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeneralCreationHistoricalBlockTest {
    @Test fun sameNameSyntheticCandidatesKeepExactIdsAcrossSearchAndCursor() {
        val worlds = mock(WorldStateReadRepository::class.java)
        val generals = mock(GeneralReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val spatial = mock(SpatialStateReadRepository::class.java)
        val world = WorldStateReadEntity(id = 1, currentYear = 190, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "block_general_create" to 0))
        `when`(worlds.findProcessWorld()).thenReturn(world)
        val topology = mock(StrategicTopologySnapshot::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(projection.topology).thenReturn(topology)
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        val positions = mock(GeneralPositionSnapshot::class.java)
        `when`(positions.statesByGeneralId).thenReturn(mapOf(
            10 to mock(GeneralPositionState::class.java),
            11 to mock(GeneralPositionState::class.java)))
        val spatialSnapshot = mock(SpatialStateReadSnapshot::class.java)
        `when`(spatialSnapshot.generalPositionSnapshot).thenReturn(positions)
        `when`(spatial.readSnapshot(1, topology)).thenReturn(spatialSnapshot)
        val source = PersonPolicyState(30, true, "synthetic_same_name", "fixture", 10)
        val first = GeneralReadEntity(id = 10, worldId = 1, name = "동명이인", cityId = 1,
            npcState = 2, nationId = 1,
            meta = mapOf("npc_org" to 1, PersonPolicyState.META_KEY to source.toMetaValue()))
        val second = GeneralReadEntity(id = 11, worldId = 1, name = "동명이인", cityId = 1,
            npcState = 2, nationId = 2,
            meta = mapOf("npc_org" to 1, PersonPolicyState.META_KEY to
                PersonPolicyState(30, true, "synthetic_same_name", "fixture", 11).toMetaValue()))
        `when`(generals.findAll()).thenReturn(listOf(second, first))
        val catalog = GeneralCreationCatalog(worlds, generals, artifacts, mock(CityGeography::class.java),
            spatial, GameApiProcessWorld(1))

        val firstPage = catalog.historical("동명", null, "AVAILABLE", "ID_ASC", null, 1)
        assertEquals(listOf(10), firstPage.people.map { it.historicalGeneralId })
        assertEquals("10", firstPage.nextCursor)
        val secondPage = catalog.historical("동명", null, "AVAILABLE", "ID_ASC", firstPage.nextCursor, 1)
        assertEquals(listOf(11), secondPage.people.map { it.historicalGeneralId })
        assertNull(secondPage.nextCursor)
        assertEquals(listOf(11), catalog.historical("동명", 2, null, null, null, 10)
            .people.map { it.historicalGeneralId })
    }

    @Test fun verificationBlockClosesASeedCandidateAndReopeningRestoresItsAvailability() {
        val worlds = mock(WorldStateReadRepository::class.java)
        val generals = mock(GeneralReadRepository::class.java)
        val artifacts = mock(ActiveWorldArtifactResolver::class.java)
        val spatial = mock(SpatialStateReadRepository::class.java)
        val world = WorldStateReadEntity(id = 1, currentYear = 190, status = "OPEN", isunited = 0,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "block_general_create" to 1))
        `when`(worlds.findProcessWorld()).thenReturn(world)

        val topology = mock(StrategicTopologySnapshot::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(projection.topology).thenReturn(topology)
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        val positions = mock(GeneralPositionSnapshot::class.java)
        `when`(positions.statesByGeneralId).thenReturn(mapOf(77 to mock(GeneralPositionState::class.java)))
        val spatialSnapshot = mock(SpatialStateReadSnapshot::class.java)
        `when`(spatialSnapshot.generalPositionSnapshot).thenReturn(positions)
        `when`(spatial.readSnapshot(1, topology)).thenReturn(spatialSnapshot)

        val seed = PersonPolicyState(30, true, "scenario_3190", "fixture", 77)
        val candidate = GeneralReadEntity(id = 77, worldId = 1, name = "장비", cityId = 10,
            npcState = 2, meta = mapOf("npc_org" to 1, PersonPolicyState.META_KEY to seed.toMetaValue()))
        `when`(generals.findAll()).thenReturn(listOf(candidate))
        val catalog = GeneralCreationCatalog(worlds, generals, artifacts, mock(CityGeography::class.java),
            spatial, GameApiProcessWorld(1))

        val blocked = catalog.historical(null, null, null, null, null, 10).people.single()
        assertEquals(77, blocked.historicalGeneralId)
        assertFalse(blocked.available)
        assertEquals("CREATION_POLICY_UNAVAILABLE", blocked.unavailableCode)

        world.config = world.config + ("block_general_create" to 0)
        val reopened = catalog.historical(null, null, null, null, null, 10).people.single()
        assertTrue(reopened.available)
        assertNull(reopened.unavailableCode)
    }
}
