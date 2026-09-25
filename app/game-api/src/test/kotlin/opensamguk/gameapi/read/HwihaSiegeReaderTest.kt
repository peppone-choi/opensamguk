package opensamguk.gameapi.read

import opensamguk.gameapi.web.HwihaSiegeController
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.HwihaDeployedCorps
import opensamguk.logic.input.HwihaDeploymentState
import opensamguk.logic.input.HwihaPhase
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus
import java.util.Optional
import kotlin.test.*

class HwihaSiegeReaderTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val sieges = mock(HwihaSiegeReadRepository::class.java)
    private val reader = HwihaSiegeReader(generals, worlds, nations, cities, retainers, sieges)
    private val controller = HwihaSiegeController(reader)
    private val world = WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA"))

    private val corps = HwihaDeployedCorps("order-1", 1, 1, null, 1, listOf(21), HwihaPhase(190, 1, 1))
    private val besieger = GeneralReadEntity(id = 1, worldId = 1, name = "공격", nationId = 1, userId = "41",
        meta = mapOf(HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(corps)).toMetaValue()))
    private val defender = GeneralReadEntity(id = 2, worldId = 1, name = "수비", nationId = 2, userId = "42")
    private val stranger = GeneralReadEntity(id = 3, worldId = 1, name = "제삼", nationId = 3, userId = "43")
    private val row = HwihaSiegeReadRow(77, "ACTIVE", 1, 1, "order-1", 1, 2, 190, 1, 1, turns = 3, morale = 2500,
        garrison = 840, endReason = null, timeline = listOf(mapOf("event" to "START")))

    private fun setup(profile: String = "HWIHA") {
        world.config = mapOf("ruleProfile" to profile)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        listOf(besieger, defender, stranger).forEach { `when`(generals.findById(it.id)).thenReturn(Optional.of(it)) }
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, worldId = 1, name = "양"),
            NationReadEntity(id = 2, worldId = 1, name = "여남")))
        `when`(cities.findById(77)).thenReturn(Optional.of(CityReadEntity(id = 77, worldId = 1, name = "익양현",
            trust = 40.0, supplyState = 0, meta = mapOf(CountyWarehouse.META_KEY to
                CountyWarehouse(77, 3, Resources(grain = 12_345)).toMetaValue()))))
        `when`(retainers.bugoksOf(1)).thenReturn(listOf(GeneralBugokReadEntity(worldId = 1, id = 21, masterGeneralId = 1,
            name = "부곡", troops = 4000, provisions = 24_000)))
        `when`(sieges.involving(1, 1)).thenReturn(listOf(row))
        `when`(sieges.involving(2, 2)).thenReturn(listOf(row))
        `when`(sieges.involving(3, 3)).thenReturn(emptyList())
    }

    @Test fun `the besieging commander sees the siege it can act on`() {
        setup()
        val body = reader.sieges(1, 41)
        assertEquals("READY", body.status)
        val siege = body.sieges.single()
        assertEquals("익양현", siege.countyName); assertEquals(12_345L, siege.grain); assertEquals(2500, siege.morale)
        assertEquals(3, siege.turns); assertEquals("양", siege.besieger.nationName); assertEquals("여남", siege.defenderNationName)
        assertEquals(4000, siege.besiegerTroops); assertEquals(true, siege.besiegerFed); assertFalse(siege.countySupplied)
        assertTrue(siege.canAct); assertTrue(siege.surrenderDemandAccepted, "morale 25% and trust 40 meet the threshold")
        assertEquals(listOf(mapOf<String, Any?>("event" to "START")), siege.timeline)
    }

    @Test fun `the defending side sees it without acting and outsiders see nothing`() {
        setup()
        val seen = reader.sieges(2, 42).sieges.single()
        assertFalse(seen.canAct)
        assertTrue(reader.sieges(3, 43).sieges.isEmpty())
    }

    @Test fun `auth follows the camp endpoints and non HWIHA worlds report a soft status`() {
        setup()
        assertEquals(HttpStatus.UNAUTHORIZED, controller.sieges(null, 1).statusCode)
        assertEquals(HttpStatus.FORBIDDEN, controller.sieges(42, 1).statusCode)
        assertEquals(HttpStatus.OK, controller.sieges(41, 1).statusCode)
        setup("SAMMO")
        assertEquals("WRONG_RULE_PROFILE", reader.sieges(1, 41).status)
    }
}
