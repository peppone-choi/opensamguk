package opensamguk.gameapi.read

import kotlin.test.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.dto.DirectoryBond
import opensamguk.logic.content.PersonBond
import opensamguk.logic.content.PersonBondKind
import opensamguk.logic.content.PersonBondState
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
            assertNotNull(person.stats); assertNotNull(person.aptitudes); assertNull(person.locationCityId)
            assertNull(person.bonds); assertNull(person.lordGeneralId); assertNull(person.role)
        }
        val admin = reader.adminPeople("타국", "ID", null, 50)
        assertNotNull(admin.people.single().stats); assertEquals(99, admin.people.single().locationCityId)
    }

    @Test fun `directory preserves oath and valid county targets while hiding strangers bonds`() {
        setup()
        // API projection unit: valid persisted domain metadata, not a new scenario county seed format.
        val oath = PersonBond(PersonBondKind.OATH, "general:2", setOf("novel:三國演義:第一回"))
        val county = PersonBond(PersonBondKind.NATIVE_COUNTY, "county:3", setOf("history:三國志:卷21"))
        val state = PersonBondState(setOf(oath, county))
        assertEquals(state, PersonBondState.read(mapOf(PersonBondState.META_KEY to state.toMetaValue())))
        for (person in listOf(self, cardPerson, sameNation, enemy)) {
            val ownOath = oath.copy(targetId = if (person.id == 2) "general:1" else "general:2")
            person.meta = person.meta + (PersonBondState.META_KEY to PersonBondState(setOf(ownOath, county)).toMetaValue())
        }
        val people = reader.people(41, "ALL", "", "ID", null, 50).people.associateBy { it.generalId }
        val expected = setOf(DirectoryBond("OATH", "general:2"), DirectoryBond("NATIVE_COUNTY", "county:3"))
        assertEquals(expected, assertNotNull(people.getValue(1).bonds).toSet())
        assertEquals(setOf(DirectoryBond("OATH", "general:1"), DirectoryBond("NATIVE_COUNTY", "county:3")),
            assertNotNull(people.getValue(2).bonds).toSet())
        assertNull(people.getValue(3).bonds)
        assertNull(people.getValue(4).bonds)
    }

    @Test fun `cursor is stable and cannot be reused for a different viewer scope or query`() {
        setup()
        val first = reader.people(41, "ALL", "", "ID", null, 2)
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(listOf(3, 4), reader.people(41, "ALL", "", "ID", cursor, 2).people.map { it.generalId })
        assertFailsWith<ResponseStatusException> { reader.people(41, "NATION", "", "ID", cursor, 2) }
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "주공", "ID", cursor, 2) }
        assertFailsWith<ResponseStatusException> { reader.adminPeople("", "ID", cursor, 2) }
        assertFailsWith<ResponseStatusException> { PeopleCursor.decode(cursor, "different-world-context") }
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "ID", "!", 50) }
    }

    @Test fun `scopes include only own nation or direct retinue and reject unsupported sort`() {
        setup()
        assertEquals(listOf(1, 2, 3), reader.people(41, "NATION", "", "ID", null, 50).people.map { it.generalId })
        assertEquals(listOf(1, 2), reader.people(41, "RETINUE", "", "ID", null, 50).people.map { it.generalId })
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "RENOWN", null, 50) }
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "ID", null, 101) }
    }

    private fun sortingFixture(): List<GeneralReadEntity> {
        setup()
        val rows = listOf(
            GeneralReadEntity(id = 4, worldId = 7, name = "베타", nationId = 30, age = 10,
                leadership = 90, strength = 80, intel = 70, politics = 60, charm = 50,
                meta = mapOf(PersonPolicyState.META_KEY to policy)),
            GeneralReadEntity(id = 2, worldId = 7, name = "알파", nationId = 20, age = 10,
                leadership = 90, strength = 80, intel = 70, politics = 60, charm = 50,
                meta = mapOf(PersonPolicyState.META_KEY to policy)),
            GeneralReadEntity(id = 5, worldId = 7, name = "오메가", nationId = 0, age = -1),
            GeneralReadEntity(id = 3, worldId = 7, name = "베타", nationId = 10, age = 30,
                leadership = 40, strength = 50, intel = 60, politics = 70, charm = 80,
                meta = mapOf(PersonPolicyState.META_KEY to policy)),
            GeneralReadEntity(id = 1, worldId = 7, name = "감마", nationId = 10, age = 20,
                leadership = 60, strength = 70, intel = 80, politics = 90, charm = 50,
                meta = mapOf(PersonPolicyState.META_KEY to policy)),
        )
        `when`(generals.findAll()).thenReturn(rows)
        `when`(nations.findAll()).thenReturn(listOf(
            NationReadEntity(id = 30, worldId = 7, name = "나"),
            NationReadEntity(id = 10, worldId = 7, name = "가"),
            NationReadEntity(id = 20, worldId = 7, name = "나")))
        return rows
    }

    @Test fun `all approved sort keys order the entire filter before keyset paging with fixed id ties`() {
        sortingFixture()
        // Literal orders include opposite stat ranks, equal-name and equal-affiliation rows, and nulls.
        val orders = mapOf(
            "ID" to (listOf(1, 2, 3, 4, 5) to listOf(5, 4, 3, 2, 1)),
            "NAME" to (listOf(1, 3, 4, 2, 5) to listOf(5, 2, 3, 4, 1)),
            "AFFILIATION" to (listOf(1, 3, 2, 4, 5) to listOf(2, 4, 1, 3, 5)),
            "LEADERSHIP" to (listOf(3, 1, 2, 4, 5) to listOf(2, 4, 1, 3, 5)),
            "STRENGTH" to (listOf(3, 1, 2, 4, 5) to listOf(2, 4, 1, 3, 5)),
            "INTEL" to (listOf(3, 2, 4, 1, 5) to listOf(1, 2, 4, 3, 5)),
            "POLITICS" to (listOf(2, 4, 3, 1, 5) to listOf(1, 3, 2, 4, 5)),
            "CHARM" to (listOf(1, 2, 4, 3, 5) to listOf(3, 1, 2, 4, 5)),
            "TOTAL" to (listOf(3, 1, 2, 4, 5) to listOf(1, 2, 4, 3, 5)),
            "COMMAND" to (listOf(3, 1, 2, 4, 5) to listOf(2, 4, 1, 3, 5)),
            "ADMINISTRATION" to (listOf(2, 4, 3, 1, 5) to listOf(1, 3, 2, 4, 5)),
            "STRATEGY" to (listOf(3, 2, 4, 1, 5) to listOf(1, 2, 4, 3, 5)),
            "ENVOY" to (listOf(2, 4, 1, 3, 5) to listOf(3, 1, 2, 4, 5)),
            "AGE" to (listOf(2, 4, 1, 3, 5) to listOf(3, 1, 2, 4, 5)),
        )
        assertEquals(PeopleSort.entries.map { it.name }.toSet(), orders.keys)
        for ((sort, expected) in orders) for ((direction, ids) in listOf("ASC" to expected.first, "DESC" to expected.second)) {
            assertEquals(ids, reader.people(41, "ALL", "", sort, null, 100, direction).people.map { it.generalId }, "$sort $direction")
            for (size in listOf(1, 2, 3)) {
                val gathered = mutableListOf<Int>()
                var cursor: String? = null
                do {
                    val page = reader.people(41, "ALL", "", sort, cursor, size, direction)
                    gathered += page.people.map { it.generalId }
                    cursor = page.nextCursor
                    assertTrue(gathered.size <= 5, "cursor must make progress")
                } while (cursor != null)
                assertEquals(ids, gathered, "$sort $direction limit=$size")
            }
        }
        assertEquals(listOf(1, 3), reader.people(41, "NATION", "", "NAME", null, 1).let { first ->
            first.people + reader.people(41, "NATION", "", "NAME", first.nextCursor, 1).people
        }.map { it.generalId })
    }

    @Test fun `public stats retain missing corrupt and negative data boundaries without widening private fields`() {
        setup()
        enemy.leadership = 91; enemy.strength = 82; enemy.intel = 73; enemy.politics = 64; enemy.charm = 55
        val public = reader.people(41, "ALL", "타국", "ID", null, 50).people.single()
        assertEquals(opensamguk.gameapi.dto.DirectoryStats(91, 82, 73, 64, 55), public.stats)
        assertEquals(opensamguk.gameapi.dto.DirectoryAptitudes(87, 67, 71, 59), public.aptitudes)
        assertNull(public.role); assertNull(public.locationCityId); assertNull(public.lordGeneralId); assertNull(public.bonds)
        for (meta in listOf<Map<String, Any?>>(emptyMap(), mapOf(PersonPolicyState.META_KEY to "corrupt"))) {
            enemy.meta = meta
            val missing = reader.people(41, "ALL", "타국", "ID", null, 50).people.single()
            assertNull(missing.stats); assertNull(missing.aptitudes)
        }
        enemy.meta = mapOf(PersonPolicyState.META_KEY to policy); enemy.charm = -1
        assertNull(reader.people(41, "ALL", "타국", "TOTAL", null, 50).people.single().stats)
    }

    @Test fun `null sort values remain last with ascending id even when descending across a boundary`() {
        val rows = sortingFixture()
        val missing = GeneralReadEntity(id = 6, worldId = 7, name = "결손", nationId = 77, age = -2)
        `when`(generals.findAll()).thenReturn(listOf(missing) + rows)
        for (sort in listOf("AFFILIATION", "TOTAL", "COMMAND", "AGE")) for (direction in listOf("ASC", "DESC")) {
            val first = reader.people(41, "ALL", "", sort, null, 5, direction)
            assertEquals(5, first.people.last().generalId)
            assertEquals(listOf(6), reader.people(41, "ALL", "", sort, first.nextCursor, 5, direction).people.map { it.generalId })
        }
    }

    @Test fun `cursor binds sort direction query scope actor world and authority but allows another page size`() {
        sortingFixture()
        val cursor = assertNotNull(reader.people(41, "ALL", "", "NAME", null, 1).nextCursor)
        assertEquals(listOf(3, 4, 2), reader.people(41, "ALL", "", "name", cursor, 3, "asc").people.map { it.generalId })
        val denied = listOf<() -> Any>(
            { reader.people(41, "ALL", "", "ID", cursor, 1) },
            { reader.people(41, "ALL", "", "NAME", cursor, 1, "DESC") },
            { reader.people(41, "ALL", "베타", "NAME", cursor, 1) },
            { reader.people(41, "NATION", "", "NAME", cursor, 1) },
            { reader.adminPeople("", "NAME", cursor, 1) },
        )
        for (call in denied) assertEquals(400, assertFailsWith<ResponseStatusException> { call() }.statusCode.value())
        `when`(owners.resolveGeneralId(42)).thenReturn(4)
        assertEquals(400, assertFailsWith<ResponseStatusException> { reader.people(42, "ALL", "", "NAME", cursor, 1) }.statusCode.value())
        `when`(retainers.findAll()).thenReturn(emptyList())
        assertEquals(400, assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", cursor, 1) }.statusCode.value())
    }

    @Test fun `changed ordering inputs membership or nation names require a fresh first page`() {
        val rows = sortingFixture()
        val cursor = assertNotNull(reader.people(41, "ALL", "", "NAME", null, 1).nextCursor)
        rows.first().strength += 1
        assertEquals(409, assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", cursor, 1) }.statusCode.value())
        rows.first().strength -= 1
        `when`(generals.findAll()).thenReturn(rows.drop(1))
        assertEquals(409, assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", cursor, 1) }.statusCode.value())
        `when`(generals.findAll()).thenReturn(rows + GeneralReadEntity(id = 99, worldId = 7, name = "신규"))
        assertEquals(409, assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", cursor, 1) }.statusCode.value())
        `when`(generals.findAll()).thenReturn(rows)
        nations.findAll().first().name = "새 이름"
        assertEquals(409, assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", cursor, 1) }.statusCode.value())
        assertEquals("READY", reader.people(41, "ALL", "", "NAME", null, 1).status)
    }

    @Test fun `names use NFC root case normalization and initials across the entire filtered set`() {
        sortingFixture()
        assertEquals(listOf(3, 4), reader.people(41, "ALL", "ㅂㅌ", "NAME", null, 50).people.map { it.generalId })
        val first = reader.people(41, "ALL", " 베타 ", "NAME", null, 1)
        val decomposed = java.text.Normalizer.normalize("베타", java.text.Normalizer.Form.NFD)
        assertEquals(listOf(4), reader.people(41, "ALL", decomposed, "NAME", first.nextCursor, 50).people.map { it.generalId })
        assertTrue(reader.people(41, "ALL", "없는이름", "NAME", null, 50).people.isEmpty())
        assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", null, 1, "SIDEWAYS") }
    }

    @Test fun `cursor rejects legacy malformed and forged anchor values`() {
        sortingFixture()
        val cursor = assertNotNull(reader.people(41, "ALL", "", "NAME", null, 1).nextCursor)
        val bytes = java.util.Base64.getUrlDecoder().decode(cursor)
        val parts = String(bytes, Charsets.UTF_8).split('|')
        fun altered(part: Int, value: String): String = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(parts.mapIndexed { i, old -> if (i == part) value else old }.joinToString("|").toByteArray())
        for (bad in listOf("!", "a".repeat(16385), altered(0, "1"), altered(3, "0"), altered(3, "999"), altered(4, "n:123"))) {
            assertEquals(400, assertFailsWith<ResponseStatusException> { reader.people(41, "ALL", "", "NAME", bad, 1) }.statusCode.value())
        }
    }

    @Test fun `total widens before addition and affiliation sort never uses nation id or private role`() {
        val rows = sortingFixture()
        rows.first().apply { leadership = Int.MAX_VALUE; strength = Int.MAX_VALUE; intel = Int.MAX_VALUE
            politics = Int.MAX_VALUE; charm = Int.MAX_VALUE }
        assertEquals(listOf(4, 1, 2, 3, 5), reader.people(41, "ALL", "", "TOTAL", null, 50, "DESC").people.map { it.generalId })
        val page = reader.people(41, "ALL", "", "AFFILIATION", null, 50)
        assertEquals(listOf(1, 3, 2, 4, 5), page.people.map { it.generalId })
        assertNull(page.people.first { it.generalId == 4 }.role)
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
        `when`(generals.findAll()).thenReturn(listOf(GeneralReadEntity(id = 1, worldId = 7, nationId = 10,
            officerLevel = 12, meta = mapOf("lord" to false)), sameNation))
        assertNull(reader.nationSummary(1, 41).lord)
        `when`(generals.findAll()).thenReturn(listOf(self, GeneralReadEntity(id = 3, worldId = 7, nationId = 10,
            officerLevel = 12, meta = mapOf("lord" to true))))
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
