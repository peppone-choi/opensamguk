package opensamguk.engine.turn

import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ReservedTurnWarDrainTest {
    private val t0 = Instant.parse("0200-01-01T12:00:00Z")
    private val hiddenSeed = "00000000000000000000000000000000"
    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `reserved sortie drains battle deltas for attacker defenders city nation and logs`() {
        val world = warWorld(
            defenders = listOf(
                defender(id = 201, crewTypeId = 1100),
                defender(id = 202, crewTypeId = 1200),
            ),
        )
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear = 184)

        val handled = handler.handle(
            100,
            ReservedTurn("che_출병", """{"destCityID":31}"""),
            year = 200,
            month = 1,
            date = "12:00",
        )

        assertFalse(handled.fellBack)
        assertEquals("che_출병", handled.definition.key)
        assertTrue(world.getGeneralById(100)!!.crew < 50_000)
        assertEquals(1100, world.getGeneralById(201)!!.crewTypeId)
        assertEquals(1200, world.getGeneralById(202)!!.crewTypeId)
        assertTrue(listOf(201, 202).any { world.getGeneralById(it)!!.crew < 200 })
        assertTrue(world.getCityById(7)!!.dead > 0)
        assertTrue(world.getCityById(31)!!.dead > 0)
        assertTrue(world.getNationById(2)!!.rice < 100_000)
        assertTrue(handler.recorder.dirtyGeneralIds().contains(100))
        assertTrue(handler.recorder.dirtyGeneralIds().any { it in listOf(201, 202) })
        assertTrue(handler.recorder.dirtyCityIds().containsAll(listOf(7, 31)))
        assertTrue(handler.recorder.dirtyNationIds().contains(2))
    }

    @Test
    fun `reserved sortie calls ConquerCity when battle succeeds`() {
        val world = warWorld(
            defenders = listOf(defender(id = 201, crew = 1, crewTypeId = 1100)),
            defenderCity = city(id = 31, nationId = 2, level = 1, defence = 1, wall = 1, defenceMax = 10, wallMax = 10),
            defenderNation = nation(id = 2, capital = 31, rice = 10_000),
        )
        val handler = ReservedTurnHandler(world, registry, hiddenSeed, startYear = 184)

        val handled = handler.handle(
            100,
            ReservedTurn("che_출병", """{"destCityID":31}"""),
            year = 200,
            month = 1,
            date = "12:00",
        )

        assertFalse(handled.fellBack)
        assertEquals(1, world.getCityById(31)!!.nationId)
        assertEquals(31, world.getGeneralById(100)!!.cityId)
        assertTrue(2 in handler.recorder.deletedNationIds())
        assertNotNull(handler.recorder.cityPatches().singleOrNull { it.id == 31 })
        assertTrue(world.consumeDirtyState().logs.any { it.text.contains("공략에 <S>성공</>했습니다.") })
    }

    private fun warWorld(
        defenders: List<TurnGeneral>,
        defenderCity: City = city(
            id = 31,
            nationId = 2,
            level = 5,
            defence = 500_000,
            wall = 500_000,
            defenceMax = 500_000,
            wallMax = 500_000,
        ),
        defenderNation: Nation = nation(id = 2, capital = 31, rice = 100_000),
    ): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(attacker()) + defenders,
            cities = listOf(city(id = 7, nationId = 1), defenderCity),
            nations = listOf(nation(id = 1, capital = 7), defenderNation),
            diplomacy = listOf(TurnDiplomacy(fromNationId = 1, toNationId = 2, state = 0, term = 0)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    private fun attacker(): TurnGeneral = general(
        id = 100,
        nationId = 1,
        cityId = 7,
        crew = 50_000,
        crewTypeId = 1100,
        leadership = 100,
        strength = 100,
        intelligence = 90,
    )

    private fun defender(id: Int, crew: Int = 200, crewTypeId: Int): TurnGeneral = general(
        id = id,
        nationId = 2,
        cityId = 31,
        crew = crew,
        crewTypeId = crewTypeId,
        leadership = 20,
        strength = 20,
        intelligence = 20,
    )

    private fun general(
        id: Int,
        nationId: Int,
        cityId: Int,
        crew: Int,
        crewTypeId: Int,
        leadership: Int,
        strength: Int,
        intelligence: Int,
    ): TurnGeneral = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = nationId,
        cityId = cityId,
        troopId = 0,
        stats = GeneralStats(leadership = leadership, strength = strength, intelligence = intelligence),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        gold = 100_000,
        rice = 100_000,
        crew = crew,
        crewTypeId = crewTypeId,
        train = 100,
        atmos = 100,
        turnTime = t0,
        meta = linkedMapOf("killturn" to 1000),
    )

    private fun city(
        id: Int,
        nationId: Int,
        level: Int = 8,
        defence: Int = 500,
        wall: Int = 500,
        defenceMax: Int = 500,
        wallMax: Int = 500,
    ): City = City(
        id = id,
        name = "c$id",
        nationId = nationId,
        level = level,
        population = 100_000,
        populationMax = 100_000,
        agriculture = 20_000,
        agricultureMax = 20_000,
        commerce = 20_000,
        commerceMax = 20_000,
        security = 20_000,
        securityMax = 20_000,
        supplyState = 1,
        frontState = 3,
        defence = defence,
        defenceMax = defenceMax,
        wall = wall,
        wallMax = wallMax,
        conflict = "{}",
        meta = linkedMapOf("trust" to 80),
    )

    private fun nation(id: Int, capital: Int, rice: Int = 100_000): Nation =
        Nation(id = id, name = "n$id", color = "#000", capitalCityId = capital, gold = 100_000, rice = rice, tech = 0.0, level = 2)
}
