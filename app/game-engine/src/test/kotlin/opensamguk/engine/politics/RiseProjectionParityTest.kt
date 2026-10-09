package opensamguk.engine.politics

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Optional
import kotlin.test.*
import opensamguk.engine.campaign.CampaignWorldFixture
import opensamguk.engine.campaign.DomesticContext
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.Troop
import opensamguk.gameapi.precheck.PoliticalOptionsService
import opensamguk.gameapi.read.*
import opensamguk.gameapi.reserve.PoliticalAdmission
import opensamguk.logic.input.*
import opensamguk.logic.world.ProvinceControlSnapshot
import org.mockito.Mockito.*

/** Real read/engine adapters on a pinned map; repository transport is mocked, not a live HTTP test. */
class RiseProjectionParityTest {
    private val fixture = CampaignWorldFixture()

    @Test fun `API and engine retain age military membership and position for zero renown rise`() {
        val route = fixture.route()
        for (age in listOf(17, 59, 60)) {
            val base = fixture.person(1, 0, route.startCity, userId = "42", lord = false)
            val actor = base.copy(age = age, troopId = 1, meta = base.meta +
                (PersonPolicyState.META_KEY to PersonPolicyState(0, true,
                    "synthetic-rise-parity", "v1", 1).toMetaValue()))
            val child = fixture.person(2, 0, route.startCity, lord = false).copy(troopId = 1)
            val world = fixture.world(listOf(actor to route.start, child to route.start), nations = emptyList(),
                retainers = listOf(Retainer(4, 1, "EXISTING", 2, "child", "lieutenant")))
            world.createTroop(Troop(1, 0, "owned troop"))
            val generals = mock(GeneralReadRepository::class.java)
            val retainers = mock(RetainerReadRepository::class.java)
            val artifacts = mock(ActiveWorldArtifactResolver::class.java)
            val spatial = mock(SpatialStateReadRepository::class.java)
            val troops = mock(TroopReadRepository::class.java)
            val rows = listOf(actor, child).map { g -> GeneralReadEntity(id = g.id, worldId = 1,
                name = g.name, userId = g.userId, nationId = g.nationId, npcState = g.npcState,
                officerLevel = g.officerLevel, troopId = g.troopId, age = g.age, meta = g.meta) }
            `when`(generals.findAll()).thenReturn(rows)
            `when`(generals.findById(1)).thenReturn(Optional.of(rows.first()))
            `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(worldId = 1,
                id = 4, masterGeneralId = 1, generalId = 2, relation = "lieutenant")))
            `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
                WorldStateReadEntity(id = 1, currentYear = 200, currentMonth = 1, currentPhase = 1,
                    config = mapOf("ruleProfile" to "HWIHA")),
                listOf(CityReadEntity(id = route.startCity, worldId = 1, nationId = 0)), fixture.bundle))
            `when`(spatial.readSnapshot(1, fixture.topology)).thenReturn(SpatialStateReadSnapshot(
                ProvinceControlSnapshot.fromTopology(fixture.topology), world.generalPositionSnapshot()!!))
            `when`(troops.findAll()).thenReturn(listOf(TroopReadEntity(worldId = 1, troopLeader = 1, nation = 0)))
            val reader = DomesticReader(generals, retainers, mock(NationReadRepository::class.java),
                artifacts, spatial, mock(CityGeography::class.java), mock(GameKvReadRepository::class.java),
                ObjectMapper(), mock(DiplomacyReadRepository::class.java), mock(SiegeReadRepository::class.java), troops)
            val api = assertNotNull(reader.snapshot().state)
            val engine = DomesticContext().projection(world)
            for (person in listOf(actor, child)) {
                assertEquals(person.age, api.person(person.id)!!.age)
                assertEquals(person.troopId, api.person(person.id)!!.troopId)
                assertTrue(api.person(person.id)!!.spatialStateAvailable)
                assertEquals(engine.person(person.id)!!.age, api.person(person.id)!!.age)
                assertEquals(engine.person(person.id)!!.troopId, api.person(person.id)!!.troopId)
                assertEquals(engine.person(person.id)!!.spatialStateAvailable, api.person(person.id)!!.spatialStateAvailable)
            }
            val request = PoliticalRequest(1, PoliticalInput.RISE)
            assertEquals(listOf(1, 2), assertIs<PoliticalAssessment.Eligible>(PoliticalRules.assess(request, api)).movingGeneralIds)
            assertEquals(listOf(1, 2), assertIs<PoliticalAssessment.Eligible>(PoliticalRules.assess(request, engine)).movingGeneralIds)
            assertTrue(PoliticalOptionsService(reader).options(1, 42).single { it.inputId == PoliticalInput.RISE }.available)
            assertEquals("{}", PoliticalAdmission(reader).canonicalArguments(PoliticalInput.RISE, 1, 42, 0, "{}"))
        }
    }
}
