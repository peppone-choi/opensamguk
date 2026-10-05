package opensamguk.gameapi.people

import kotlin.test.*
import opensamguk.gameapi.dto.*
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.*
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.PersonPolicyState
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.util.Optional

class PersonDetailReaderTest {
    private val owners = mock(GeneralResolver::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val geography = mock(CityGeography::class.java)
    private val camp = mock(CampReader::class.java)
    private val reader = PersonDetailReader(owners, generals, worlds, nations, retainers, artifacts, geography, camp)
    private val policy = PersonPolicyState(20, true, "synthetic-qa:person-detail", "1", 1).toMetaValue()
    private val self = GeneralReadEntity(id = 1, worldId = 7, userId = "41", name = "주공", nationId = 10, cityId = 3,
        leadership = 60, strength = 70, intel = 80, politics = 90, charm = 50, injury = 0,
        meta = mapOf("lord" to true, PersonPolicyState.META_KEY to policy))
    private val retainer = GeneralReadEntity(id = 2, worldId = 7, name = "휘하", nationId = 10, cityId = 3, injury = 30,
        leadership = 70, strength = 90, intel = 30, politics = 20, charm = 40, meta = mapOf(PersonPolicyState.META_KEY to policy))
    private val colleague = GeneralReadEntity(id = 3, worldId = 7, name = "동료", nationId = 10, cityId = 9, injury = 50,
        meta = mapOf("lord" to true, PersonPolicyState.META_KEY to policy))
    private val stranger = GeneralReadEntity(id = 4, worldId = 7, userId = "42", name = "타국", nationId = 20, cityId = 9,
        meta = mapOf(PersonPolicyState.META_KEY to policy))
    private val bond = BondDto("HYANGDANG", "향당", "패국 초현", "沛國譙縣", sameAsLord = true)

    private fun setup(cards: List<GeneralRetainerReadEntity> = listOf(GeneralRetainerReadEntity(worldId = 7, id = 11,
                          masterGeneralId = 1, generalId = 2)),
                      retinueStatus: String = "READY", format: String = "GENERAL_RETAINER_CAMPAIGN") {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 7, config = mapOf("worldFormat" to format)))
        `when`(owners.resolveGeneralId(41)).thenReturn(1)
        for (g in listOf(self, retainer, colleague, stranger)) `when`(generals.findById(g.id)).thenReturn(Optional.of(g))
        `when`(retainers.findAll()).thenReturn(cards)
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 10, worldId = 7, name = "우리", color = "#123456"),
            NationReadEntity(id = 20, worldId = 7, name = "다른", color = "#654321")))
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(WorldStateReadEntity(id = 7),
            listOf(CityReadEntity(id = 3, worldId = 7, name = "현"), CityReadEntity(id = 9, worldId = 7, name = "남현")), bundle))
        `when`(geography.places(bundle)).thenReturn(mapOf(3 to CityGeography.Place("甲군", "county:3", countyHanja = "甲縣", displayName = "양적현")))
        `when`(camp.retinue(1, 41)).thenReturn(CampRetinueResponse(retinueStatus, 30, 12, false, listOf(PersonCardDto(
            retainerId = 11, generalId = 2, name = "휘하", picture = null, imageServer = 0, loyalty = 85, roleLabel = "참모",
            taskLabel = "대기", stats = null, cost = 12, aptitudes = null, bonds = listOf(bond), departureOrder = null))))
    }

    @Test fun `self opens private fields from its own record but has no retinue-shaped bond or card source`() {
        setup()
        val out = requireNotNull(reader.person(1, 1, 41))
        assertEquals("READY", out.status); assertEquals("SELF", out.relation); assertEquals("주공", out.name)
        assertEquals(DirectoryAffiliation(10, "우리", "#123456"), out.affiliation)
        assertEquals(DirectoryStats(60, 70, 80, 90, 50), out.stats); assertNotNull(out.aptitudes)
        assertEquals("LORD", out.role); assertNull(out.lordGeneralId)
        assertEquals(PersonLocationDto(3, "양적현"), out.location); assertEquals(false, out.injured)
        assertNull(out.bonds); assertNull(out.retinue)
        assertEquals("NO_SOURCE", out.unavailableReasons["/bonds"]); assertEquals("NO_SOURCE", out.unavailableReasons["/retinue"])
        assertNull(out.placement); assertNull(out.offices)
        assertEquals("CONTRACT_PENDING", out.unavailableReasons["/placement"]); assertEquals("CONTRACT_PENDING", out.unavailableReasons["/offices"])
        verifyNoInteractions(camp)
    }

    @Test fun `direct retainer gets the retinue card bonds injury and location`() {
        setup()
        val out = requireNotNull(reader.person(2, 1, 41))
        assertEquals("READY", out.status); assertEquals("RETINUE", out.relation)
        assertEquals("RETAINER", out.role); assertEquals(1, out.lordGeneralId)
        assertEquals(listOf(bond), out.bonds); assertEquals(true, out.injured)
        assertEquals(PersonRetinueCardDto(11, 85, 12, "참모", "대기", null), out.retinue)
        assertEquals(PersonLocationDto(3, "양적현"), out.location)
        assertNull(out.unavailableReasons["/bonds"])
    }

    @Test fun `same nation grants public fields only and never private ones`() {
        setup()
        val out = requireNotNull(reader.person(3, 1, 41))
        assertEquals("SAME_NATION", out.relation); assertEquals("동료", out.name); assertNotNull(out.stats)
        assertNull(out.role); assertNull(out.lordGeneralId); assertNull(out.location); assertNull(out.bonds)
        assertNull(out.injured); assertNull(out.retinue)
        for (field in listOf("/role", "/lordGeneralId", "/location", "/bonds", "/injured", "/retinue"))
            assertEquals("NOT_AUTHORIZED", out.unavailableReasons[field], field)
        verifyNoInteractions(camp, artifacts)
    }

    @Test fun `other nation stays OTHER and is never turned into an enemy flag`() {
        setup()
        val out = requireNotNull(reader.person(4, 1, 41))
        assertEquals("OTHER", out.relation); assertEquals(DirectoryAffiliation(20, "다른", "#654321"), out.affiliation)
        assertNull(out.location); assertEquals("NOT_AUTHORIZED", out.unavailableReasons["/location"])
    }

    @Test fun `nation zero is never shared between two unaffiliated people`() {
        setup()
        `when`(generals.findById(1)).thenReturn(Optional.of(GeneralReadEntity(id = 1, worldId = 7, userId = "41", name = "재야", nationId = 0, meta = self.meta)))
        `when`(generals.findById(4)).thenReturn(Optional.of(GeneralReadEntity(id = 4, worldId = 7, name = "재야2", nationId = 0, meta = self.meta)))
        val out = requireNotNull(reader.person(4, 1, 41))
        assertEquals("OTHER", out.relation); assertNull(out.affiliation)
    }

    @Test fun `duplicate cards cannot decide the relation and are never guessed as retinue`() {
        setup(cards = listOf(GeneralRetainerReadEntity(worldId = 7, id = 11, masterGeneralId = 1, generalId = 2),
            GeneralRetainerReadEntity(worldId = 7, id = 12, masterGeneralId = 4, generalId = 2)))
        val out = requireNotNull(reader.person(2, 1, 41))
        assertEquals("UNKNOWN", out.relation); assertNull(out.retinue); assertNull(out.bonds)
        assertEquals("NOT_AUTHORIZED", out.unavailableReasons["/retinue"])
        verifyNoInteractions(camp)
    }

    @Test fun `borrowed body fails before the target is read`() {
        setup()
        assertFailsWith<CampForbidden> { reader.person(3, 4, 41) }
        verify(generals, never()).findById(3)
        verifyNoInteractions(retainers, nations, camp, artifacts)
    }

    @Test fun `missing target is not found and a non-positive target is a bad request`() {
        setup()
        `when`(generals.findById(99)).thenReturn(Optional.empty())
        assertNull(reader.person(99, 1, 41))
        assertEquals(400, assertFailsWith<ResponseStatusException> { reader.person(0, 1, 41) }.statusCode.value())
    }

    @Test fun `unsupported world format is reported without reading the target`() {
        setup(format = "NOT_A_CAMPAIGN_FORMAT")
        val out = requireNotNull(reader.person(3, 1, 41))
        assertEquals(PersonDetailDto("UNSUPPORTED_WORLD_FORMAT", 3), out)
        verify(generals, never()).findById(3)
    }

    @Test fun `damaged sources stay null with a reason and make the read partial`() {
        setup(retinueStatus = "UNAVAILABLE")
        `when`(generals.findById(2)).thenReturn(Optional.of(GeneralReadEntity(id = 2, worldId = 7, name = "휘하", nationId = 10,
            cityId = 404, injury = 140, meta = emptyMap())))
        val out = requireNotNull(reader.person(2, 1, 41))
        assertEquals("PARTIAL", out.status); assertEquals("RETINUE", out.relation)
        assertNull(out.stats); assertNull(out.aptitudes); assertNull(out.injured); assertNull(out.location)
        assertNull(out.bonds); assertNull(out.retinue)
        for (field in listOf("/stats", "/aptitudes", "/injured", "/location", "/bonds", "/retinue"))
            assertEquals("INVALID_SOURCE", out.unavailableReasons[field], field)
    }

    @Test fun `a target or card from another world is a conflict`() {
        setup()
        `when`(generals.findById(3)).thenReturn(Optional.of(GeneralReadEntity(id = 3, worldId = 8, name = "딴세계", meta = self.meta)))
        assertEquals(409, assertFailsWith<ResponseStatusException> { reader.person(3, 1, 41) }.statusCode.value())
    }
}
