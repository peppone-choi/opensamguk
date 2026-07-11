package opensamguk.engine.turn

import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerTurnOverlayTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun general(id: Int, nationId: Int = 1, cityId: Int = 5, gold: Int = 100, intel: Int = 60) =
        TurnGeneral(
            id = id,
            name = "g$id",
            nationId = nationId,
            cityId = cityId,
            troopId = 0,
            stats = GeneralStats(leadership = 80, strength = 70, intelligence = intel, politics = 44, charm = 55),
            experience = 12,
            dedication = 34,
            officerLevel = 0,
            gold = gold,
            rice = 50,
            injury = 0,
            turnTime = t0,
            meta = linkedMapOf("intel_exp" to 7, "explevel" to 2),
        )

    private fun city(id: Int = 5, nationId: Int = 1, agri: Int = 100, agriMax: Int = 1000) = City(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = 5,
        agriculture = agri,
        agricultureMax = agriMax,
        commerce = 200,
        commerceMax = 2000,
        supplyState = 1,
        frontState = 0,
        meta = linkedMapOf("trust" to 80),
    )

    private fun nation(id: Int = 1, level: Int = 3) =
        Nation(id = id, name = "n$id", color = "#000", level = level, capitalCityId = 5)

    private fun baseState() = TurnWorldState(
        id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0,
    )

    private fun worldWith() = InMemoryTurnWorld(
        WorldSnapshot(
            state = baseState(),
            generals = listOf(general(1)),
            cities = listOf(city(5)),
            nations = listOf(nation(1)),
        ),
    )

    @Test
    fun `read falls through to world rows`() {
        val world = worldWith()
        val overlay = PerTurnOverlay(world)

        assertEquals(100, overlay.getGeneral(1)?.gold)
        assertEquals(100, overlay.getCity(5)?.agriculture)
        assertEquals(3, overlay.getNation(1)?.level)
        assertNull(overlay.getGeneral(999))
    }

    @Test
    fun `staged write visible in overlay but world unchanged`() {
        val world = worldWith()
        val overlay = PerTurnOverlay(world)

        overlay.stageGeneral(general(1, gold = 250))
        overlay.stageCity(city(5, agri = 175))

        // overlay sees the staged values
        assertEquals(250, overlay.getGeneral(1)?.gold)
        assertEquals(175, overlay.getCity(5)?.agriculture)
        assertTrue(overlay.isGeneralStaged(1))
        assertTrue(overlay.isCityStaged(5))

        // the world is untouched until applied (the dirty-free apply path is F2/F3)
        assertEquals(100, world.getGeneralById(1)?.gold)
        assertEquals(100, world.getCityById(5)?.agriculture)
        assertTrue(world.consumeDirtyState().generals.isEmpty(), "overlay staging never marks the world dirty")
    }

    @Test
    fun `engine to logic conversion maps slice subset and carries meta`() {
        val world = worldWith()
        val overlay = PerTurnOverlay(world)

        val g = overlay.getLogicGeneral(1)!!
        assertEquals(60, g.intel, "intel <- stats.intelligence")
        assertEquals(44, g.politics)
        assertEquals(55, g.charm)
        assertEquals(12.0, g.experience, "experience Int -> raw Double")
        assertEquals(34.0, g.dedication, "dedication Int -> raw Double")
        assertEquals(7, g.meta["intel_exp"], "meta carried verbatim")
        // meta insertion order preserved
        assertEquals(listOf("intel_exp", "explevel"), g.meta.keys.toList())

        val c = overlay.getLogicCity(5)!!
        assertEquals(100, c.agriculture)
        assertEquals(1000, c.agricultureMax)
        assertEquals(1, c.supplyState)
        assertEquals(80.0, c.trust, "trust <- meta[\"trust\"] as Double")

        val n = overlay.getLogicNation(1)!!
        assertEquals(3, n.level)
        assertEquals(5, n.capitalCityId)
    }

    @Test
    fun `WorldStateViewAdapter resolves general city nation as logic entities`() {
        val world = worldWith()
        val overlay = PerTurnOverlay(world)
        val view = WorldStateViewAdapter(
            overlay,
            env = linkedMapOf("year" to 200, "startYear" to 180, "develCost" to 60),
            args = linkedMapOf("amount" to 1),
        )

        assertTrue(view.has(RequirementKey.General(1)))
        assertTrue(view.has(RequirementKey.City(5)))
        assertTrue(view.has(RequirementKey.Nation(1)))
        assertFalse(view.has(RequirementKey.General(999)))

        assertTrue(view.get(RequirementKey.General(1)) is LogicGeneral)
        assertTrue(view.get(RequirementKey.City(5)) is LogicCity)
        assertTrue(view.get(RequirementKey.Nation(1)) is LogicNation)
        assertNull(view.get(RequirementKey.City(999)))

        // env/arg pass through (full-mode env supplied by the handler / shared env-builder)
        assertTrue(view.has(RequirementKey.Env("develCost")))
        assertEquals(60, view.get(RequirementKey.Env("develCost")))
        assertEquals(1, view.get(RequirementKey.Arg("amount")))
        assertFalse(view.has(RequirementKey.Env("missing")))
    }

    @Test
    fun `adapter reads staged overlay rows ahead of world`() {
        val world = worldWith()
        val overlay = PerTurnOverlay(world)
        overlay.stageGeneral(general(1, gold = 250))
        val view = WorldStateViewAdapter(overlay)

        val g = view.get(RequirementKey.General(1)) as LogicGeneral
        assertEquals(250, g.gold, "adapter sees the overlay-staged general")
        assertEquals(100, world.getGeneralById(1)?.gold, "world still untouched")
    }
}
