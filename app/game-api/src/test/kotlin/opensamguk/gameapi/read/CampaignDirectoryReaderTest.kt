package opensamguk.gameapi.read

import kotlin.test.*
import opensamguk.gameapi.owner.GeneralResolver
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
    private val cities = mock(CityReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val owners = mock(GeneralResolver::class.java)
    private val reader = CampaignDirectoryReader(worlds, generals, nations, cities, retainers, owners)
    private val policy = PersonPolicyState(20, true, "synthetic-qa:directory", "1", 1).toMetaValue()
    private val self = GeneralReadEntity(id = 1, worldId = 7, userId = "41", name = "주공", nationId = 10,
        cityId = 3, leadership = 60, strength = 70, intel = 80, politics = 90, charm = 50,
        crew = 99999, officerLevel = 1, meta = mapOf("lord" to true, PersonPolicyState.META_KEY to policy))
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
        `when`(cities.findAll()).thenReturn(listOf(CityReadEntity(id = 3, worldId = 7, nationId = 10,
            population = 500, defense = 999, meta = mapOf(
                CountyWarehouse.META_KEY to CountyWarehouse(3, 0, Resources(11, 22, 33, 44, 55)).toMetaValue(),
                CityMilitaryState.META_KEY to CityMilitaryState(50, 50, 300).toMetaValue())),
            CityReadEntity(id = 9, worldId = 7, nationId = 20, population = 10000)))
        `when`(retainers.allBugoks()).thenReturn(listOf(GeneralBugokReadEntity(worldId = 7, id = 1,
            masterGeneralId = 1, troops = 70), GeneralBugokReadEntity(worldId = 7, id = 2, masterGeneralId = 4, troops = 800)))
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

    @Test fun `nation summary uses county military and warehouses without legacy crew or office inference`() {
        setup()
        val out = reader.nationSummary(1, 41)
        assertEquals("READY", out.status); assertEquals(500L, out.population)
        assertEquals(300L, out.troops?.city); assertEquals(70L, out.troops?.bugok)
        assertEquals(11L, out.stockTotal?.money); assertEquals(55L, out.stockTotal?.horses)
        assertEquals(1, out.retinueCount); assertNull(out.lord, "two persisted lords are ambiguous, not officer-level candidates")
        val admin = reader.adminNations()
        assertEquals("PARTIAL", admin.status)
        assertEquals(listOf(10, 20), admin.nations.map { it.nation?.id })
        assertEquals(800L, admin.nations[1].troops?.bugok)
    }

    @Test fun `missing warehouse and malformed military state stay unknown instead of zero`() {
        setup()
        `when`(cities.findAll()).thenReturn(listOf(CityReadEntity(id = 3, worldId = 7, nationId = 10,
            meta = mapOf(CityMilitaryState.META_KEY to mapOf("troops" to 999)))))
        val out = reader.nationSummary(1, 41)
        assertEquals("PARTIAL", out.status); assertNull(out.stockTotal); assertNull(out.troops?.city)
    }

    @Test fun `ownership denial precedes private queries and foreign world data fail closed`() {
        setup()
        assertFailsWith<CampForbidden> { reader.nationSummary(4, 41) }
        verifyNoInteractions(worlds, nations, cities, retainers)
        `when`(generals.findAll()).thenReturn(listOf(GeneralReadEntity(id = 99, worldId = 8)))
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "ID", null, 50) }
    }
}
