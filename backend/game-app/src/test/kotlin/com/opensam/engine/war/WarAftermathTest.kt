package com.opensam.engine.war

import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class WarAftermathTest {

    private val service = WarAftermath()

    private fun buildConfig() = WarAftermathConfig(
        initialNationGenLimit = 1,
        techLevelIncYear = 5,
        initialAllowedTechLevel = 1,
        maxTechLevel = 12,
        defaultCityWall = 1000,
        baseGold = 0,
        baseRice = 0,
        castleCrewTypeId = 1000,
    )

    private fun buildCity(id: Long, nationId: Long): City {
        return City(
            id = id,
            worldId = 1,
            name = "City$id",
            level = 2,
            nationId = nationId,
            pop = 10_000,
            popMax = 10_000,
            agri = 1000,
            agriMax = 1000,
            comm = 1000,
            commMax = 1000,
            secu = 1000,
            secuMax = 1000,
            def = 100,
            defMax = 200,
            wall = 100,
            wallMax = 200,
            supplyState = 1,
            frontState = 0,
            meta = mutableMapOf(),
        )
    }

    private fun buildNation(id: Long): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "Nation$id",
            color = "#000000",
            capitalCityId = id,
            chiefGeneralId = 0,
            gold = 1000,
            rice = 1000,
            power = 0,
            level = 1,
            typeCode = "test",
            tech = 1000f,
            meta = mutableMapOf("tech" to 1000),
        )
    }

    private fun buildGeneral(id: Long, nationId: Long, cityId: Long): General {
        return General(
            id = id,
            worldId = 1,
            name = "General$id",
            nationId = nationId,
            cityId = cityId,
            troopId = 0,
            leadership = 70,
            strength = 70,
            intel = 70,
            experience = 100,
            dedication = 100,
            officerLevel = 3,
            injury = 0,
            gold = 1000,
            rice = 1000,
            crew = 1000,
            crewType = 1000,
            train = 80,
            atmos = 80,
            age = 20,
            npcState = 0,
            killTurn = 24,
            meta = mutableMapOf(),
        )
    }

    @Test
    fun `updates tech and diplomacy deltas`() {
        val attackerNation = buildNation(1)
        val defenderNation = buildNation(2)
        val attackerCity = buildCity(1, 1)
        val defenderCity = buildCity(2, 2)
        val attacker = buildGeneral(1, 1, 1)

        val outcome = service.resolveWarAftermath(
            WarAftermathInput(
                battle = WarBattleOutcome(
                    attacker = attacker,
                    defenders = emptyList(),
                    defenderCity = defenderCity,
                    logs = emptyList(),
                    conquered = false,
                    reports = listOf(
                        WarUnitReport(
                            id = attacker.id,
                            type = "general",
                            name = attacker.name,
                            isAttacker = true,
                            killed = 100,
                            dead = 50,
                        ),
                    ),
                ),
                attackerNation = attackerNation,
                defenderNation = defenderNation,
                attackerCity = attackerCity,
                defenderCity = defenderCity,
                nations = listOf(attackerNation, defenderNation),
                cities = listOf(attackerCity, defenderCity),
                generals = listOf(attacker),
                config = buildConfig(),
                time = WarTimeContext(
                    year = 200,
                    month = 1,
                    startYear = 180,
                ),
            ),
        )

        assertEquals(1001, attackerNation.meta["tech"])
        assertEquals(1001, defenderNation.meta["tech"])
        assertEquals(2, outcome.diplomacyDeltas.size)
        assertEquals(60, attackerCity.meta["dead"])
        assertEquals(90, defenderCity.meta["dead"])
    }

    @Test
    fun `applies conquest collapse rewards`() {
        val attackerNation = buildNation(1)
        val defenderNation = buildNation(2).apply {
            gold = 5000
            rice = 6000
        }
        val attackerCity = buildCity(1, 1)
        val defenderCity = buildCity(2, 2).apply {
            meta["conflict"] = "{\"1\":100}"
        }
        val attacker = buildGeneral(1, 1, 1)
        val defender = buildGeneral(2, 2, 2)

        val outcome = service.resolveWarAftermath(
            WarAftermathInput(
                battle = WarBattleOutcome(
                    attacker = attacker,
                    defenders = listOf(defender),
                    defenderCity = defenderCity,
                    logs = emptyList(),
                    conquered = true,
                    reports = listOf(
                        WarUnitReport(
                            id = attacker.id,
                            type = "general",
                            name = attacker.name,
                            isAttacker = true,
                            killed = 10,
                            dead = 5,
                        ),
                        WarUnitReport(
                            id = defenderCity.id,
                            type = "city",
                            name = defenderCity.name,
                            isAttacker = false,
                            killed = 0,
                            dead = 0,
                        ),
                    ),
                ),
                attackerNation = attackerNation,
                defenderNation = defenderNation,
                attackerCity = attackerCity,
                defenderCity = defenderCity,
                nations = listOf(attackerNation, defenderNation),
                cities = listOf(attackerCity, defenderCity),
                generals = listOf(attacker, defender),
                config = buildConfig(),
                time = WarTimeContext(
                    year = 200,
                    month = 1,
                    startYear = 180,
                ),
                rng = Random(0),
            ),
        )

        assertEquals(true, outcome.conquest?.nationCollapsed)
        assertTrue(attackerNation.gold > 1000)
        assertTrue(attackerNation.rice > 1000)
        assertTrue(defender.experience < 100)
        assertTrue(defender.dedication < 100)
        assertEquals(attackerNation.id, defenderCity.nationId)
        assertEquals("{}", defenderCity.meta["conflict"])
    }
}
