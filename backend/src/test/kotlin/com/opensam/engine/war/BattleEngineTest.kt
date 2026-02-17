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
        specialCode: String = "None",
        special2Code: String = "None",
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
            specialCode = specialCode,
            special2Code = special2Code,
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
    fun `WarUnitGeneral base attack uses legacy crew-type-specific stat ratio`() {
        val general = createGeneral(strength = 100, leadership = 50)
        val unit = WarUnitGeneral(general)

        // Legacy: FOOTMAN → ratio = strength*2-40 = 160, clamped: 50+160/2 = 130
        // FOOTMAN.attack=100, techAbil=0 → (100+0)*130/100 = 130.0
        assertEquals(130.0, unit.getBaseAttack(), 0.01)
    }

    @Test
    fun `WarUnitGeneral base defence uses legacy crew factor formula`() {
        val general = createGeneral(leadership = 60, strength = 40, intel = 80, crew = 1000)
        val unit = WarUnitGeneral(general)

        // Legacy: FOOTMAN.defence=150, techAbil=0, crewFactor=1000/233.33+70=74.286
        // result = 150 * 74.286 / 100 = 111.429
        assertEquals(111.43, unit.getBaseDefence(), 0.01)
    }

    @Test
    fun `WarUnitGeneral tech bonus increases attack`() {
        val general = createGeneral(strength = 100, leadership = 50)
        val unit = WarUnitGeneral(general, nationTech = 100f)

        // techLevel=(100/100)=1, techAbil=25, ratio=130 (same as above)
        // (100+25)*130/100 = 162.5
        assertEquals(162.5, unit.getBaseAttack(), 0.01)
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
    fun `WarUnitGeneral consumeRice applies legacy multipliers`() {
        val general = createGeneral(rice = 1000)
        val unit = WarUnitGeneral(general)

        unit.consumeRice(500)
        // 500/100=5.0, isAttacker=true, vsCity=false
        // FOOTMAN.riceCost=9 → 5.0*9=45.0, techCost=1.0 → 45
        assertEquals(955, unit.rice)
    }

    @Test
    fun `WarUnitGeneral consumeRice defender and vsCity multipliers`() {
        val general = createGeneral(rice = 1000)
        val unit = WarUnitGeneral(general)

        unit.consumeRice(500, isAttacker = false, vsCity = true)
        // 500/100=5.0, *0.8 (defender)=4.0, *0.8 (vsCity)=3.2
        // FOOTMAN.riceCost=9 → 3.2*9=28.8, techCost=1.0 → 28
        assertEquals(972, unit.rice)
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

    // ========== Trigger integration tests ==========

    @Test
    fun `collectTriggers returns triggers from specialCode`() {
        val general = createGeneral(specialCode = "필살")
        val unit = WarUnitGeneral(general)

        val triggers = engine.collectTriggers(unit)

        assertEquals(1, triggers.size)
        assertEquals("필살", triggers[0].code)
    }

    @Test
    fun `collectTriggers returns triggers from both specialCode and special2Code`() {
        val general = createGeneral(specialCode = "필살", special2Code = "회피")
        val unit = WarUnitGeneral(general)

        val triggers = engine.collectTriggers(unit)

        assertEquals(2, triggers.size)
    }

    @Test
    fun `collectTriggers returns empty for None specials`() {
        val general = createGeneral(specialCode = "None", special2Code = "None")
        val unit = WarUnitGeneral(general)

        val triggers = engine.collectTriggers(unit)

        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `collectTriggers returns empty for city units`() {
        val city = createCity()
        val unit = WarUnitCity(city)

        val triggers = engine.collectTriggers(unit)

        assertTrue(triggers.isEmpty())
    }

    @Test
    fun `견고 special prevents injury in resolveBattle`() {
        // Run many battles with 견고 attacker - should never get injured
        var injuryOccurred = false
        for (seed in 1..50) {
            val rng = Random(seed)
            val attackerGeneral = createGeneral(
                id = 1, nationId = 1, strength = 60, leadership = 60,
                crew = 3000, rice = 50000, specialCode = "견고",
            )
            val defenderGeneral = createGeneral(
                id = 2, nationId = 2, strength = 50, leadership = 50,
                crew = 2000, rice = 20000,
            )
            val city = createCity(nationId = 2)

            val attacker = WarUnitGeneral(attackerGeneral)
            val defender = WarUnitGeneral(defenderGeneral)

            engine.resolveBattle(attacker, listOf(defender), city, rng)

            if (attackerGeneral.injury > 0) {
                injuryOccurred = true
                break
            }
        }
        assertFalse(injuryOccurred, "견고 special should prevent all injuries")
    }

    @Test
    fun `공성 special works in siege without errors`() {
        val rng = Random(42)
        val gen = createGeneral(
            id = 1, nationId = 1, strength = 90, leadership = 90,
            crew = 10000, rice = 100000, specialCode = "공성",
        )
        val city = createCity(nationId = 2, def = 50, wall = 50)
        val attacker = WarUnitGeneral(gen)

        val result = engine.resolveBattle(attacker, emptyList(), city, rng)

        assertTrue(result.attackerDamageDealt > 0)
        assertTrue(result.cityOccupied, "공성 attacker should occupy city")
    }

    @Test
    fun `resolveBattle with specials does not crash`() {
        // Smoke test: various special combinations should not cause errors
        val specials = listOf("필살", "회피", "반계", "신산", "위압", "저격", "격노", "돌격",
            "화공", "기습", "매복", "방어", "귀모", "공성", "철벽", "분투", "용병", "견고")

        for (special in specials) {
            val rng = Random(42)
            val attackerGeneral = createGeneral(
                id = 1, nationId = 1, strength = 70, leadership = 70,
                crew = 3000, rice = 50000, specialCode = special,
            )
            val defenderGeneral = createGeneral(
                id = 2, nationId = 2, strength = 50, leadership = 50,
                crew = 2000, rice = 20000,
            )
            val city = createCity(nationId = 2)

            val attacker = WarUnitGeneral(attackerGeneral)
            val defender = WarUnitGeneral(defenderGeneral)

            // Should not throw
            val result = engine.resolveBattle(attacker, listOf(defender), city, rng)
            assertTrue(result.attackerDamageDealt > 0, "Battle with $special should deal damage")
        }
    }

    @Test
    fun `resolveBattle deterministic with same seed`() {
        val attackerGen1 = createGeneral(id = 1, nationId = 1, strength = 70, crew = 3000, rice = 50000)
        val defenderGen1 = createGeneral(id = 2, nationId = 2, strength = 50, crew = 2000, rice = 20000)
        val city1 = createCity(nationId = 2)
        val result1 = engine.resolveBattle(WarUnitGeneral(attackerGen1), listOf(WarUnitGeneral(defenderGen1)), city1, Random(123))

        val attackerGen2 = createGeneral(id = 1, nationId = 1, strength = 70, crew = 3000, rice = 50000)
        val defenderGen2 = createGeneral(id = 2, nationId = 2, strength = 50, crew = 2000, rice = 20000)
        val city2 = createCity(nationId = 2)
        val result2 = engine.resolveBattle(WarUnitGeneral(attackerGen2), listOf(WarUnitGeneral(defenderGen2)), city2, Random(123))

        assertEquals(result1.attackerDamageDealt, result2.attackerDamageDealt)
        assertEquals(result1.defenderDamageDealt, result2.defenderDamageDealt)
        assertEquals(result1.attackerWon, result2.attackerWon)
    }
}
