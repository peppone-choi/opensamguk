package opensamguk.engine.campaign

import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.gameapi.read.*
import opensamguk.logic.council.CurrentRulerBinding
import opensamguk.logic.input.*
import opensamguk.logic.world.ProvinceControlSnapshot

/** Exercise the actual API and engine adapters on one pinned map with synthetic identity rows. */
class RetireProjectionTest {
    private val fixture = CampaignWorldFixture()
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val geography = mock(CityGeography::class.java)
    private val reader = DomesticReader(generals, retainers, nations, artifacts, spatial, geography,
        mock(GameKvReadRepository::class.java), ObjectMapper(), mock(DiplomacyReadRepository::class.java),
        mock(SiegeReadRepository::class.java))

    @Test fun `actual API and engine projections use column age and the same durable ruler identity`() {
        val route = fixture.route()
        for (age in listOf(59, 60)) {
            for (rulerId in listOf(null, 981, 982)) {
                val binding = rulerId?.let { CurrentRulerBinding.with(emptyMap(), it, "seed-ruler",
                    CurrentRulerBinding.SCENARIO_SEED_SOURCE) }.orEmpty()
                // A conflicting metadata age and distant current year cannot qualify the actor.
                val actor = fixture.person(981, 1, route.startCity, userId = "42").copy(age = age,
                    meta = mapOf(LordStatus.META_KEY to true, "age" to 100, "birthYear" to 1))
                val heir = fixture.person(982, 1, route.startCity, lord = false).copy(age = 30)
                val world = fixture.world(listOf(actor to route.start, heir to route.start),
                    nations = listOf(Nation(1, "국", "#111111", chiefGeneralId = rulerId, meta = binding)),
                    retainers = listOf(Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "guest")))
                val topology = fixture.topology
                `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
                    WorldStateReadEntity(id = 1, currentYear = 2200, currentMonth = 1, currentPhase = 1,
                        config = mapOf("ruleProfile" to "HWIHA")), emptyList(), fixture.bundle))
                `when`(generals.findAll()).thenReturn(listOf(
                    GeneralReadEntity(id = actor.id, worldId = 1, name = actor.name, userId = actor.userId,
                        nationId = 1, npcState = actor.npcState, officerLevel = actor.officerLevel,
                        age = age, meta = actor.meta),
                    GeneralReadEntity(id = heir.id, worldId = 1, name = heir.name, nationId = 1,
                        npcState = 2, age = heir.age, meta = heir.meta)))
                `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(worldId = 1, id = 11,
                    masterGeneralId = actor.id, generalId = heir.id, name = heir.name, relation = "guest")))
                `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, worldId = 1, name = "국", meta = binding)))
                `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
                    ProvinceControlSnapshot.fromTopology(topology, emptyList()), world.generalPositionSnapshot()!!))
                `when`(geography.places(fixture.bundle)).thenReturn(emptyMap())
                val apiSnapshot = reader.snapshot()
                assertNull(apiSnapshot.failure)
                val api = assertNotNull(apiSnapshot.state)
                val engine = DomesticContext().projection(world)
                assertEquals(age, api.person(actor.id)!!.age)
                assertEquals(engine.person(actor.id)!!.age, api.person(actor.id)!!.age)
                assertEquals(rulerId, api.nation(1)!!.chiefGeneralId)
                assertEquals(engine.nation(1)!!.chiefGeneralId, api.nation(1)!!.chiefGeneralId)
                val request = RetireRequest(actor.id, heir.id)
                val apiAssessment = RetireRules.assess(request, api)
                val engineAssessment = RetireRules.assess(request, engine)
                if (age < 60 || rulerId == heir.id) {
                    val expected = if (age < 60) RetireFailure.AGE_TOO_YOUNG else RetireFailure.STATE_UNAVAILABLE
                    assertEquals(expected, assertIs<RetireAssessment.Rejected>(apiAssessment).reason)
                    assertEquals(expected, assertIs<RetireAssessment.Rejected>(engineAssessment).reason)
                } else {
                    assertIs<RetireAssessment.Eligible>(apiAssessment)
                    assertIs<RetireAssessment.Eligible>(engineAssessment)
                }
            }
        }
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, worldId = 1,
            meta = mapOf(CurrentRulerBinding.META_KEY to mapOf("generalId" to 981)))))
        val corrupt = reader.snapshot()
        assertNull(corrupt.state)
        assertEquals("UNAVAILABLE", corrupt.failure)
    }
}
