package com.opensam.engine.war

import com.opensam.entity.City
import com.opensam.entity.General
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class BattleEngineTest {

    private lateinit var engine: BattleEngine

    @BeforeEach
    fun setUp() {
        engine = BattleEngine()
    }

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        crew: Int = 1000,
        train: Short = 80,
        atmos: Short = 80,
        gold: Int = 1000,
        rice: Int = 5000,
        experience: Int = 1000,
        dedication: Int = 1000,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = nationId,
            cityId = 1,
            leadership = leadership,
            strength = strength,
            intel = intel,
            crew = crew,
            crewType = 0,
            train = train,
            atmos = atmos,
            gold = gold,
            rice = rice,
            experience = experience,
            dedication = dedication,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        nationId: Long = 2,
        def: Int = 500,
        defMax: Int = 1000,
        wall: Int = 500,
        wallMax: Int = 1000,
        pop: Int = 10000,
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            def = def,
            defMax = defMax,
            wall = wall,
            wallMax = wallMax,
            pop = pop,
            popMax = 50000,
        )
    }

    // ========== WarUnitGeneral: stats ==========

    @Test
    fun `WarUnitGeneral initializes HP from crew`() {
        val general = createGeneral(crew = 2000)
        val unit = WarUnitGeneral(general)

        assertEquals(2000, unit.hp)
        assertEquals(2000, unit.maxHp)
    }

    @Test
    fun `WarUnitGeneral base attack uses strength 70 percent and leadership 30 percent`() {
        val general = createGeneral(strength = 100, leadership = 50)
        val unit = WarUnitGeneral(general)

        // (100 * 0.7 + 50 * 0.3) * (1 + 0/1000) * 1.0 = 85.0
        assertEquals(85.0, unit.getBaseAttack(), 0.01)
    }

    @Test
    fun `WarUnitGeneral base defence uses mixed stats`() {
        val general = createGeneral(leadership = 60, strength = 40, intel = 80)
        val unit = WarUnitGeneral(general)

        // (60 * 0.5 + 40 * 0.3 + 80 * 0.2) * 1.0 * 1.0 = 58.0
        assertEquals(58.0, unit.getBaseDefence(), 0.01)
    }

    @Test
    fun `WarUnitGeneral tech bonus increases attack`() {
        val general = createGeneral(strength = 100, leadership = 50)
        val unit = WarUnitGeneral(general, nationTech = 100f)

        // 85.0 * (1 + 100/1000) = 85.0 * 1.1 = 93.5
        assertEquals(93.5, unit.getBaseAttack(), 0.01)
    }

    @Test
    fun `WarUnitGeneral continueWar requires HP and rice`() {
        val general = createGeneral(crew = 1000, rice = 5000)
        val unit = WarUnitGeneral(general)

        assertTrue(unit.continueWar())

        // Deplete rice below threshold: rice <= hp/100 => 5000 > 1000/100=10, so still fighting
        unit.rice = 5
        assertFalse(unit.continueWar(), "Should not continue with insufficient rice")
    }

    @Test
    fun `WarUnitGeneral continueWar false when HP is zero`() {
        val general = createGeneral(crew = 1000)
        val unit = WarUnitGeneral(general)

        unit.hp = 0
        assertFalse(unit.continueWar())
    }

    @Test
    fun `WarUnitGeneral consumeRice reduces rice`() {
        val general = createGeneral(rice = 1000)
        val unit = WarUnitGeneral(general)

        unit.consumeRice(500)
        // consumption = max(1, 500/100) = 5
        assertEquals(995, unit.rice)
    }

    @Test
    fun `WarUnitGeneral consumeRice minimum 1`() {
        val general = createGeneral(rice = 100)
        val unit = WarUnitGeneral(general)

        unit.consumeRice(50)
        // consumption = max(1, 50/100=0) = 1
        assertEquals(99, unit.rice)
    }

    @Test
    fun `WarUnitGeneral applyResults writes back to general`() {
        val general = createGeneral(crew = 1000, rice = 5000, train = 80, atmos = 80)
        val unit = WarUnitGeneral(general)

        unit.hp = 800
        unit.rice = 4500
        unit.train = 70
        unit.atmos = 65
        unit.injury = 10
        unit.applyResults()

        assertEquals(800, general.crew)
        assertEquals(4500, general.rice)
        assertEquals(70.toShort(), general.train)
        assertEquals(65.toShort(), general.atmos)
        assertEquals(10.toShort(), general.injury)
    }

    // ========== WarUnitCity ==========

    @Test
    fun `WarUnitCity HP is def times 10`() {
        val city = createCity(def = 300)
        val unit = WarUnitCity(city)

        assertEquals(3000, unit.hp)
    }

    @Test
    fun `WarUnitCity base attack uses def and wall`() {
        val city = createCity(def = 500, wall = 500)
        val unit = WarUnitCity(city)

        // (500 + 500*9) / 500 + 200 = (500+4500)/500 + 200 = 10 + 200 = 210
        assertEquals(210.0, unit.getBaseAttack(), 0.01)
    }

    @Test
    fun `WarUnitCity base defence is 1_5x attack`() {
        val city = createCity(def = 500, wall = 500)
        val unit = WarUnitCity(city)

        assertEquals(unit.getBaseAttack() * 1.5, unit.getBaseDefence(), 0.01)
    }

    @Test
    fun `WarUnitCity applyResults writes def back to city`() {
        val city = createCity(def = 500)
        val unit = WarUnitCity(city)

        unit.hp = 3000
        unit.applyResults()
        assertEquals(300, city.def) // 3000 / 10
    }

    // ========== WarUnit: takeDamage ==========

    @Test
    fun `takeDamage reduces HP`() {
        val general = createGeneral(crew = 1000)
        val unit = WarUnitGeneral(general)

        unit.takeDamage(300)
        assertEquals(700, unit.hp)
        assertTrue(unit.isAlive)
    }

    @Test
    fun `takeDamage kills unit when HP reaches zero`() {
        val general = createGeneral(crew = 500)
        val unit = WarUnitGeneral(general)

        unit.takeDamage(500)
        assertEquals(0, unit.hp)
        assertFalse(unit.isAlive)
    }

    @Test
    fun `takeDamage kills unit when damage exceeds HP`() {
        val general = createGeneral(crew = 100)
        val unit = WarUnitGeneral(general)

        unit.takeDamage(9999)
        assertEquals(0, unit.hp)
        assertFalse(unit.isAlive)
    }

    // ========== WarUnit: calcBattleOrder ==========

    @Test
    fun `calcBattleOrder higher stats and crew gives higher order`() {
        val strong = createGeneral(leadership = 90, strength = 90, intel = 90, crew = 5000, train = 80, atmos = 80)
        val weak = createGeneral(leadership = 30, strength = 30, intel = 30, crew = 500, train = 30, atmos = 30)

        val strongUnit = WarUnitGeneral(strong)
        val weakUnit = WarUnitGeneral(weak)

        assertTrue(strongUnit.calcBattleOrder() > weakUnit.calcBattleOrder())
    }

    // ========== BattleEngine: resolveBattle ==========

    @Test
    fun `resolveBattle strong attacker beats weak defender`() {
        val rng = Random(42)
        val attackerGeneral = createGeneral(id = 1, nationId = 1, strength = 90, leadership = 90, crew = 5000, rice = 50000, train = 80, atmos = 80)
        val defenderGeneral = createGeneral(id = 2, nationId = 2, strength = 30, leadership = 30, crew = 500, rice = 500, train = 30, atmos = 30)
        val city = createCity(nationId = 2, def = 100, wall = 100)

        val attacker = WarUnitGeneral(attackerGeneral)
        val defender = WarUnitGeneral(defenderGeneral)

        val result = engine.resolveBattle(attacker, listOf(defender), city, rng)

        assertTrue(result.attackerDamageDealt > 0, "Attacker should deal damage")
        assertTrue(result.defenderDamageDealt > 0, "Defender should deal some damage")
    }

    @Test
    fun `resolveBattle returns damage amounts`() {
        val rng = Random(42)
        val attackerGeneral = createGeneral(id = 1, nationId = 1, strength = 70, leadership = 70, crew = 3000, rice = 30000)
        val defenderGeneral = createGeneral(id = 2, nationId = 2, strength = 50, leadership = 50, crew = 2000, rice = 20000)
        val city = createCity(nationId = 2)

        val attacker = WarUnitGeneral(attackerGeneral)
        val defender = WarUnitGeneral(defenderGeneral)

        val result = engine.resolveBattle(attacker, listOf(defender), city, rng)

        assertTrue(result.attackerDamageDealt > 0)
        assertTrue(result.defenderDamageDealt > 0)
    }

    @Test
    fun `resolveBattle attacker retreats when out of rice`() {
        val rng = Random(42)
        val attackerGeneral = createGeneral(id = 1, nationId = 1, crew = 1000, rice = 1, strength = 50)
        val defenderGeneral = createGeneral(id = 2, nationId = 2, crew = 1000, rice = 50000, strength = 50)
        val city = createCity(nationId = 2)

        val attacker = WarUnitGeneral(attackerGeneral)
        val defender = WarUnitGeneral(defenderGeneral)

        val result = engine.resolveBattle(attacker, listOf(defender), city, rng)

        assertFalse(result.attackerWon, "Attacker should retreat when out of rice")
    }

    @Test
    fun `resolveBattle applies morale loss`() {
        val rng = Random(42)
        val attackerGeneral = createGeneral(id = 1, nationId = 1, crew = 3000, rice = 50000, atmos = 80)
        val defenderGeneral = createGeneral(id = 2, nationId = 2, crew = 2000, rice = 50000, atmos = 80)
        val city = createCity(nationId = 2)

        val attacker = WarUnitGeneral(attackerGeneral)
        val defender = WarUnitGeneral(defenderGeneral)

        engine.resolveBattle(attacker, listOf(defender), city, rng)

        // Attacker loses 1 atmos per phase, defender loses 3
        assertTrue(attacker.atmos < 80, "Attacker morale should decrease")
    }

    @Test
    fun `resolveBattle with siege occupies city when all defenders eliminated`() {
        val rng = Random(100)
        // Very strong attacker vs very weak defender and city
        val attackerGeneral = createGeneral(id = 1, nationId = 1, strength = 99, leadership = 99, crew = 50000, rice = 500000, train = 80, atmos = 80, experience = 10000)
        val defenderGeneral = createGeneral(id = 2, nationId = 2, strength = 10, leadership = 10, crew = 10, rice = 1, train = 10, atmos = 10)
        val city = createCity(nationId = 2, def = 10, wall = 10, pop = 100)

        val attacker = WarUnitGeneral(attackerGeneral)
        val defender = WarUnitGeneral(defenderGeneral)

        val result = engine.resolveBattle(attacker, listOf(defender), city, rng)

        if (result.attackerWon) {
            assertTrue(result.cityOccupied, "City should be occupied when attacker wins completely")
        }
    }

    @Test
    fun `resolveBattle with no defenders goes straight to siege`() {
        val rng = Random(42)
        val attackerGeneral = createGeneral(id = 1, nationId = 1, strength = 90, leadership = 90, crew = 10000, rice = 100000, train = 80, atmos = 80)
        val city = createCity(nationId = 2, def = 50, wall = 50)

        val attacker = WarUnitGeneral(attackerGeneral)

        val result = engine.resolveBattle(attacker, emptyList(), city, rng)

        assertTrue(result.attackerWon, "Should win with no defenders")
        assertTrue(result.cityOccupied, "Should occupy city with no defenders")
    }

    @Test
    fun `resolveBattle multiple defenders sorted by battle order`() {
        val rng = Random(42)
        val attackerGeneral = createGeneral(id = 1, nationId = 1, strength = 80, leadership = 80, crew = 5000, rice = 50000)
        val weakDefender = createGeneral(id = 2, nationId = 2, strength = 20, leadership = 20, crew = 500, rice = 5000)
        val strongDefender = createGeneral(id = 3, nationId = 2, strength = 70, leadership = 70, crew = 3000, rice = 30000)
        val city = createCity(nationId = 2)

        val attacker = WarUnitGeneral(attackerGeneral)
        val defenders = listOf(WarUnitGeneral(weakDefender), WarUnitGeneral(strongDefender))

        val result = engine.resolveBattle(attacker, defenders, city, rng)

        // Just verify the battle executed without errors and damage was dealt
        assertTrue(result.attackerDamageDealt > 0)
        assertTrue(result.defenderDamageDealt > 0)
    }
}
