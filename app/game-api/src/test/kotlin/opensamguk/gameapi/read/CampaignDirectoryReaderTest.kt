package opensamguk.gameapi.read

import kotlin.test.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.world.StrategicRouteProjection
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.input.PersonPolicyState
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class CampaignDirectoryReaderTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val owners = mock(GeneralResolver::class.java)
    private val reader = CampaignDirectoryReader(worlds, generals, nations, artifacts, retainers, owners)
    private val policy = PersonPolicyState(20, true, "synthetic-qa:directory", "1", 1).toMetaValue()
    private val self = GeneralReadEntity(id = 1, worldId = 7, userId = "41", name = "주공", nationId = 10,
        cityId = 3, leadership = 60, strength = 70, intel = 80, politics = 90, charm = 50,
        crew = 99999, officerLevel = 12, meta = mapOf("lord" to true, PersonPolicyState.META_KEY to policy))
    private val cardPerson = GeneralReadEntity(id = 2, worldId = 7, name = "휘하", nationId = 10, cityId = 3,
        meta = mapOf(PersonPolicyState.META_KEY to policy))
    private val sameNation = GeneralReadEntity(id = 3, worldId = 7, name = "동료", nationId = 10, cityId = 9,
        meta = mapOf("lord" to true, PersonPolicyState.META_KEY to policy))
    private val enemy = GeneralReadEntity(id = 4, worldId = 7, userId = "42", name = "타국", nationId = 20,
        cityId = 99, meta = mapOf(PersonPolicyState.META_KEY to policy))
    private fun setup() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 7,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")))
        `when`(owners.resolveGeneralId(41)).thenReturn(1)
        `when`(generals.findById(1)).thenReturn(Optional.of(self))
        `when`(generals.findById(4)).thenReturn(Optional.of(enemy))
        `when`(generals.findAll()).thenReturn(listOf(enemy, cardPerson, self, sameNation))
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 10, worldId = 7, name = "우리", color = "#123456"),
            NationReadEntity(id = 20, worldId = 7, name = "다른", color = "#654321")))
        `when`(retainers.findAll()).thenReturn(listOf(GeneralRetainerReadEntity(worldId = 7, id = 1,
            masterGeneralId = 1, generalId = 2), GeneralRetainerReadEntity(worldId = 7, id = 2,
            masterGeneralId = 4, generalId = null)))
        selectCounties(listOf(CityReadEntity(id = 3, worldId = 7, nationId = 10,
            population = 500, defense = 999, meta = mapOf(
                CountyWarehouse.META_KEY to CountyWarehouse(3, 0, Resources(11, 22, 33, 44, 55)).toMetaValue(),
                CityMilitaryState.META_KEY to CityMilitaryState(50, 50, 300).toMetaValue())),
            CityReadEntity(id = 9, worldId = 7, nationId = 20, population = 10000),
            CityReadEntity(id = 8, worldId = 7, nationId = 10, population = 999999, defense = 999999)))
        `when`(retainers.allBugoks()).thenReturn(listOf(GeneralBugokReadEntity(worldId = 7, id = 1,
            masterGeneralId = 1, troops = 70), GeneralBugokReadEntity(worldId = 7, id = 2, masterGeneralId = 4, troops = 800)))
    }

    private fun selectCounties(rows: List<CityReadEntity>, adminIds: Set<Int> = setOf(3, 9), worldId: Int = 7) {
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        val projection = mock(StrategicRouteProjection::class.java)
        `when`(bundle.projection).thenReturn(projection)
        `when`(projection.administrativeCountyIds).thenReturn(adminIds)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(
            WorldStateReadEntity(id = worldId), rows, bundle))
    }

    @Test fun `directory hides private fields even from a same-nation stranger`() {
        setup()
        val out = reader.people(41, "ALL", "", "ID", null, 50)
        assertEquals(listOf(1, 2, 3, 4), out.people.map { it.generalId })
        assertNotNull(out.people[0].stats); assertNotNull(out.people[1].aptitudes)
        assertEquals("LORD", out.people[0].role); assertEquals("RETAINER", out.people[1].role)
        assertEquals(1, out.people[1].lordGeneralId)
        for (person in out.people.drop(2)) {
            assertNull(person.stats); assertNull(person.aptitudes); assertNull(person.locationCityId)
            assertNull(person.bonds); assertNull(person.lordGeneralId); assertNull(person.role)
        }
        val admin = reader.adminPeople("타국", "ID", null, 50)
        assertNotNull(admin.people.single().stats); assertEquals(99, admin.people.single().locationCityId)
    }

    @Test fun `cursor is stable and cannot be reused for a different viewer scope or query`() {
        setup()
        val first = reader.people(41, "ALL", "", "ID", null, 2)
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(listOf(3, 4), reader.people(41, "ALL", "", "ID", cursor, 2).people.map { it.generalId })
        assertFailsWith<ResponseStatusException> { reader.people(41, "NATION", "", "ID", cursor, 2) }
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "주공", "ID", cursor, 2) }
        assertFailsWith<ResponseStatusException> { reader.adminPeople("", "ID", cursor, 2) }
        assertFailsWith<ResponseStatusException> { PeopleCursor.decode(cursor, PeopleCursor.context(8, 1, "ALL", "")) }
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "ID", "!", 50) }
    }

    @Test fun `scopes include only own nation or direct retinue and reject unsupported sort`() {
        setup()
        assertEquals(listOf(1, 2, 3), reader.people(41, "NATION", "", "ID", null, 50).people.map { it.generalId })
        assertEquals(listOf(1, 2), reader.people(41, "RETINUE", "", "ID", null, 50).people.map { it.generalId })
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "RENOWN", null, 50) }
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "ID", null, 101) }
    }

    @Test fun `nation summary uses administrative counties and canonical ruler with several lords`() {
        setup()
        val out = reader.nationSummary(1, 41)
        assertEquals("READY", out.status); assertEquals(500L, out.population)
        assertEquals(300L, out.troops?.city); assertEquals(70L, out.troops?.bugok)
        assertEquals(11L, out.stockTotal?.money); assertEquals(55L, out.stockTotal?.horses)
        assertEquals(1, out.retinueCount); assertEquals(1, out.countyCount)
        assertEquals(1, out.lord?.generalId, "only the canonical ruler is selected among several lords")
        val admin = reader.adminNations()
        assertEquals("READY", admin.status)
        assertEquals(listOf(10, 20), admin.nations.map { it.nation?.id })
        assertEquals(800L, admin.nations[1].troops?.bugok)
    }

    @Test fun `absent warehouse contributes zero while other warehouse stock remains known`() {
        setup()
        val selected = assertNotNull(artifacts.resolve())
        selectCounties(selected.cities + CityReadEntity(id = 5, worldId = 7, nationId = 10,
            population = 100, defense = 20), setOf(3, 5, 9))
        val out = reader.nationSummary(1, 41)
        assertEquals("READY", out.status); assertEquals(11L, out.stockTotal?.money)
        assertEquals(2, out.countyCount); assertEquals(600L, out.population)
        assertEquals(320L, out.troops?.city)
        assertEquals(0L, reader.adminNations().nations[1].stockTotal?.money)
    }

    @Test fun `corrupt warehouse keeps stock unknown without losing military state`() {
        setup()
        selectCounties(listOf(CityReadEntity(id = 3, worldId = 7, nationId = 10, defense = 20,
            meta = mapOf(CountyWarehouse.META_KEY to mapOf("stock" to 999)))))
        val out = reader.nationSummary(1, 41)
        assertEquals("PARTIAL", out.status); assertNull(out.stockTotal); assertEquals(20L, out.troops?.city)
    }

    @Test fun `corrupt military state keeps troops unknown while warehouse absence is valid`() {
        setup()
        selectCounties(listOf(CityReadEntity(id = 3, worldId = 7, nationId = 10,
            meta = mapOf(CityMilitaryState.META_KEY to mapOf("troops" to 999)))))
        val out = reader.nationSummary(1, 41)
        assertEquals("PARTIAL", out.status); assertEquals(0L, out.stockTotal?.money); assertNull(out.troops?.city)
    }

    @Test fun `ruler uses canonical office and lord flag including ambiguity`() {
        setup()
        `when`(generals.findAll()).thenReturn(listOf(self.copy(meta = mapOf("lord" to false)), sameNation))
        assertNull(reader.nationSummary(1, 41).lord)
        `when`(generals.findAll()).thenReturn(listOf(self, sameNation.copy(officerLevel = 12)))
        assertNull(reader.nationSummary(1, 41).lord)
    }

    @Test fun `missing artifact identity is unavailable and foreign artifact worlds are rejected`() {
        setup()
        `when`(artifacts.resolve()).thenReturn(null)
        assertEquals("UNAVAILABLE", reader.nationSummary(1, 41).status)
        assertEquals("UNAVAILABLE", reader.adminNations().status)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(WorldStateReadEntity(id = 7), emptyList(), null))
        assertEquals("UNAVAILABLE", reader.nationSummary(1, 41).status)
        assertEquals("UNAVAILABLE", reader.adminNations().status)
        selectCounties(emptyList(), worldId = 8)
        assertFailsWith<ResponseStatusException> { reader.nationSummary(1, 41) }
        assertFailsWith<ResponseStatusException> { reader.adminNations() }
    }

    @Test fun `an old body retaining user id cannot impersonate the resolved live character`() {
        setup()
        `when`(owners.resolveGeneralId(41)).thenReturn(null)
        assertFailsWith<CampForbidden> { reader.nationSummary(1, 41) }
        assertEquals("NO_GENERAL", reader.people(41, "ALL", "", "ID", null, 50).status)
        verifyNoInteractions(generals, worlds, nations, artifacts, retainers)
    }

    @Test fun `ownership denial precedes private queries and foreign world data fail closed`() {
        setup()
        assertFailsWith<CampForbidden> { reader.nationSummary(4, 41) }
        verifyNoInteractions(worlds, nations, artifacts, retainers)
        `when`(generals.findAll()).thenReturn(listOf(GeneralReadEntity(id = 99, worldId = 8)))
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "ID", null, 50) }
    }
}
