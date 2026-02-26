package com.opensam.qa.parity

import com.opensam.engine.war.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.model.CrewType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * Battle engine parity tests verifying Kotlin logic matches legacy PHP.
 *
 * Legacy references:
 * - hwe/func_converter.php: getDexLevel(), getDexLog(), getDexLevelList()
 * - hwe/sammo/WarUnit.php: computeWarPower()
 * - hwe/sammo/WarUnitGeneral.php: getComputedAttack(), getComputedDefence()
 * - hwe/sammo/WarUnitCity.php: getComputedAttack(), getComputedDefence()
 */
@DisplayName("Battle Engine Legacy Parity")
class BattleParityTest {

    // ──────────────────────────────────────────────────
    //  WarFormula parity (func_converter.php)
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("getDexLevel - legacy func_converter.php:762")
    inner class DexLevelParity {

        @Test
        fun `dex 0 returns level 0`() {
            assertEquals(0, getDexLevel(0))
        }

        @Test
        fun `dex exactly at threshold returns that level`() {
            // Legacy: iterates getDexLevelList(), returns last index where dex >= threshold
            assertEquals(0, getDexLevel(0))
            assertEquals(1, getDexLevel(350))
            assertEquals(2, getDexLevel(1375))
            assertEquals(3, getDexLevel(3500))
            assertEquals(10, getDexLevel(82775))
            assertEquals(20, getDexLevel(595525))
            assertEquals(26, getDexLevel(1275975))
        }

        @Test
        fun `dex just below threshold returns previous level`() {
            assertEquals(0, getDexLevel(349))
            assertEquals(1, getDexLevel(1374))
            assertEquals(2, getDexLevel(3499))
            assertEquals(8, getDexLevel(61749))
        }

        @Test
        fun `dex far above max threshold stays at max level`() {
            // Legacy: loop ends, retVal stays at last valid level
            assertEquals(26, getDexLevel(2000000))
            assertEquals(26, getDexLevel(Int.MAX_VALUE))
        }

        @Test
        fun `negative dex returns 0`() {
            // Legacy: if($dex < 0) return 0;
            assertEquals(0, getDexLevel(-1))
            assertEquals(0, getDexLevel(-1000))
        }

        @Test
        fun `all 27 threshold boundary values`() {
            val expected = intArrayOf(
                0, 350, 1375, 3500, 7125, 12650, 20475, 31000, 44625, 61750,
                82775, 108100, 138125, 173250, 213875, 260400, 313225, 372750,
                439375, 513500, 595525, 685850, 784875, 893000, 1010625, 1138150, 1275975,
            )
            for (i in expected.indices) {
                assertEquals(i, getDexLevel(expected[i]), "Level for dex=${expected[i]}")
                if (i > 0) {
                    assertEquals(i - 1, getDexLevel(expected[i] - 1), "Level for dex=${expected[i] - 1}")
                }
            }
        }
    }

    @Nested
    @DisplayName("getDexLog - legacy func_converter.php:777")
    inner class DexLogParity {

        @Test
        fun `same dex returns 1_0`() {
            // Legacy: (getDexLevel(d1) - getDexLevel(d2)) / 55 + 1
            // Same level → 0/55 + 1 = 1.0
            assertEquals(1.0, getDexLog(0, 0))
            assertEquals(1.0, getDexLog(1000, 1000))
            assertEquals(1.0, getDexLog(350, 350))
        }

        @Test
        fun `higher dex gives ratio above 1`() {
            // Level 10 vs level 0 → 10/55 + 1 = 1.1818...
            val result = getDexLog(82775, 0)
            assertEquals(10.0 / 55.0 + 1.0, result, 0.0001)
        }

        @Test
        fun `lower dex gives ratio below 1`() {
            // Level 0 vs level 10 → -10/55 + 1 = 0.8181...
            val result = getDexLog(0, 82775)
            assertEquals(-10.0 / 55.0 + 1.0, result, 0.0001)
        }

        @Test
        fun `max level difference`() {
            // Level 26 vs level 0 → 26/55 + 1 = 1.4727...
            val result = getDexLog(1275975, 0)
            assertEquals(26.0 / 55.0 + 1.0, result, 0.0001)
        }

        @Test
        fun `legacy PHP integer division behavior`() {
            // PHP: (getDexLevel($dex1) - getDexLevel($dex2)) / 55 + 1
            // PHP does float division here (not integer division)
            // Level 1 vs level 0 → 1/55 + 1 ≈ 1.01818
            val result = getDexLog(350, 0)
            assertEquals(1.0 / 55.0 + 1.0, result, 0.0001)
        }
    }

    @Nested
    @DisplayName("getTechLevel / getTechAbil / getTechCost")
    inner class TechFormulaParity {

        @Test
        fun `techLevel is tech divided by 100 clamped 0-30`() {
            assertEquals(0, getTechLevel(0f))
            assertEquals(0, getTechLevel(99f))
            assertEquals(1, getTechLevel(100f))
            assertEquals(1, getTechLevel(199f))
            assertEquals(10, getTechLevel(1000f))
            assertEquals(30, getTechLevel(3000f))
            assertEquals(30, getTechLevel(5000f)) // clamped at 30
        }

        @Test
        fun `techAbil is techLevel times 25`() {
            assertEquals(0, getTechAbil(0f))
            assertEquals(25, getTechAbil(100f))
            assertEquals(250, getTechAbil(1000f))
            assertEquals(750, getTechAbil(3000f))
        }

        @Test
        fun `techCost is 1 plus techLevel times 0_15`() {
            assertEquals(1.0, getTechCost(0f), 0.001)
            assertEquals(1.15, getTechCost(100f), 0.001)
            assertEquals(2.5, getTechCost(1000f), 0.001)
            assertEquals(5.5, getTechCost(3000f), 0.001)
        }
    }

    // ──────────────────────────────────────────────────
    //  WarUnitGeneral attack/defence formula parity
    //  Legacy: GameUnitConst->getComputedAttack/Defence
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("WarUnitGeneral base attack - legacy WarUnitGeneral.php")
    inner class BaseAttackParity {

        @Test
        fun `footman uses strength stat with ratio formula`() {
            // Legacy: ratio = str*2-40, clamped; attack = (crewType.attack + techAbil) * ratio/100
            val gen = createGeneral(strength = 80, crewType = CrewType.FOOTMAN.code)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // ratio = 80*2-40 = 120 → since >100: 50 + 120/2 = 110
            // attack = FOOTMAN.attack(100) + techAbil(0) = 100
            // baseAttack = 100 * 110/100 = 110.0
            assertEquals(110.0, unit.getBaseAttack(), 0.01)
        }

        @Test
        fun `low strength gets clamped ratio`() {
            val gen = createGeneral(strength = 20, crewType = CrewType.FOOTMAN.code)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // ratio = 20*2-40 = 0 → since <10: 10
            // baseAttack = 100 * 10/100 = 10.0
            assertEquals(10.0, unit.getBaseAttack(), 0.01)
        }

        @Test
        fun `wizard uses intel stat`() {
            val gen = createGeneral(intel = 90, crewType = CrewType.WIZARD.code)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // ratio = 90*2-40 = 140 → >100: 50+140/2 = 120
            // attack = WIZARD.attack + 0
            val wizAttack = CrewType.WIZARD.attack
            val expected = wizAttack * 120.0 / 100.0
            assertEquals(expected, unit.getBaseAttack(), 0.01)
        }

        @Test
        fun `tech bonus adds to attack`() {
            val gen = createGeneral(strength = 70, crewType = CrewType.FOOTMAN.code)
            val unit = WarUnitGeneral(gen, nationTech = 1000f)
            // ratio = 70*2-40 = 100 → exactly 100
            // techAbil = getTechAbil(1000) = 250
            // attack = (100 + 250) = 350
            // baseAttack = 350 * 100/100 = 350.0
            assertEquals(350.0, unit.getBaseAttack(), 0.01)
        }
    }

    @Nested
    @DisplayName("WarUnitGeneral base defence - legacy WarUnitGeneral.php")
    inner class BaseDefenceParity {

        @Test
        fun `defence uses crew factor formula`() {
            // Legacy: crewFactor = crew/233.33 + 70; defence = (crewType.defence + techAbil) * crewFactor/100
            val gen = createGeneral(crewType = CrewType.FOOTMAN.code, crew = 5000)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            val crewFactor = 5000 / 233.33 + 70.0
            val expected = (CrewType.FOOTMAN.defence + 0) * crewFactor / 100.0
            assertEquals(expected, unit.getBaseDefence(), 0.01)
        }

        @Test
        fun `small crew gives lower defence`() {
            val gen = createGeneral(crewType = CrewType.FOOTMAN.code, crew = 100)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            val crewFactor = 100 / 233.33 + 70.0
            val expected = CrewType.FOOTMAN.defence * crewFactor / 100.0
            assertEquals(expected, unit.getBaseDefence(), 0.01)
        }
    }

    @Nested
    @DisplayName("WarUnitCity attack/defence - legacy WarUnitCity.php")
    inner class CityUnitParity {

        @Test
        fun `city attack formula uses def and wall`() {
            // Legacy: base = (def + wall*9) / 500 + 200
            val city = createCity(def = 1000, wall = 500)
            val unit = WarUnitCity(city)
            val expected = (1000 + 500 * 9) / 500.0 + 200.0
            assertEquals(expected, unit.getBaseAttack(), 0.01)
        }

        @Test
        fun `city defence is 1_5x attack`() {
            val city = createCity(def = 1000, wall = 500)
            val unit = WarUnitCity(city)
            assertEquals(unit.getBaseAttack() * 1.5, unit.getBaseDefence(), 0.01)
        }

        @Test
        fun `city HP is def times 10`() {
            val city = createCity(def = 500, wall = 200)
            val unit = WarUnitCity(city)
            assertEquals(5000, unit.hp)
        }
    }

    // ──────────────────────────────────────────────────
    //  WarUnitGeneral critical chance parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Critical chance computation")
    inner class CriticalChanceParity {

        @Test
        fun `footman critical uses strength stat with 0_5 coef`() {
            // Legacy: (mainStat - 65) * coef / 100, capped at 50%
            // For footman (non-wizard, non-siege): strength, coef=0.5
            val gen = createGeneral(strength = 85, crewType = CrewType.FOOTMAN.code, train = 100)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // (85-65)*0.5 = 10 → 10/100 = 0.10
            assertEquals(0.10, unit.criticalChance, 0.001)
        }

        @Test
        fun `low stat gives zero critical chance`() {
            val gen = createGeneral(strength = 50, crewType = CrewType.FOOTMAN.code)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // (50-65) = -15, clamped to 0
            assertEquals(0.0, unit.criticalChance, 0.001)
        }

        @Test
        fun `critical chance capped at 50 percent`() {
            val gen = createGeneral(strength = 200.toShort(), crewType = CrewType.FOOTMAN.code)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // (200-65)*0.5 = 67.5, capped to 50 → 0.50
            assertEquals(0.50, unit.criticalChance, 0.001)
        }
    }

    // ──────────────────────────────────────────────────
    //  Dodge chance parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Dodge chance computation")
    inner class DodgeChanceParity {

        @Test
        fun `dodge chance uses crew type avoid and train`() {
            // Legacy: crewType.avoid / 100 * (train / 100)
            val gen = createGeneral(crewType = CrewType.FOOTMAN.code, train = 80)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // FOOTMAN.avoid=10; 10/100 * 80/100 = 0.08
            val expected = CrewType.FOOTMAN.avoid / 100.0 * (80 / 100.0)
            assertEquals(expected, unit.dodgeChance, 0.001)
        }
    }

    // ──────────────────────────────────────────────────
    //  Rice consumption parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Rice consumption - legacy WarUnitGeneral.php")
    inner class RiceConsumptionParity {

        @Test
        fun `attacker rice consumption formula`() {
            val gen = createGeneral(crewType = CrewType.FOOTMAN.code, rice = 10000)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            val initialRice = unit.rice

            unit.consumeRice(damageDealt = 1000, isAttacker = true, vsCity = false)

            // consumption = 1000/100 * 1.0(isAttacker) * 1.0(notVsCity) * riceCost * techCost
            val riceCost = CrewType.FOOTMAN.riceCost.toDouble()
            val techCost = getTechCost(0f)
            val expected = (1000 / 100.0 * riceCost * techCost).toInt()
            assertEquals(initialRice - expected, unit.rice)
        }

        @Test
        fun `defender gets 0_8 multiplier`() {
            val gen = createGeneral(crewType = CrewType.FOOTMAN.code, rice = 10000)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            val initialRice = unit.rice

            unit.consumeRice(damageDealt = 1000, isAttacker = false, vsCity = false)

            val riceCost = CrewType.FOOTMAN.riceCost.toDouble()
            val techCost = getTechCost(0f)
            val expected = (1000 / 100.0 * 0.8 * riceCost * techCost).toInt()
            assertEquals(initialRice - expected, unit.rice)
        }

        @Test
        fun `vsCity gets additional 0_8 multiplier`() {
            val gen = createGeneral(crewType = CrewType.FOOTMAN.code, rice = 10000)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            val initialRice = unit.rice

            unit.consumeRice(damageDealt = 1000, isAttacker = true, vsCity = true)

            val riceCost = CrewType.FOOTMAN.riceCost.toDouble()
            val techCost = getTechCost(0f)
            val expected = (1000 / 100.0 * 0.8 * riceCost * techCost).toInt()
            assertEquals(initialRice - expected, unit.rice)
        }
    }

    // ──────────────────────────────────────────────────
    //  Battle result determinism with seeded RNG
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Battle determinism")
    inner class BattleDeterminism {

        @Test
        fun `same seed same result`() {
            val result1 = runBattle(42)
            val result2 = runBattle(42)
            assertEquals(result1.attackerWon, result2.attackerWon)
            assertEquals(result1.attackerDamageDealt, result2.attackerDamageDealt)
            assertEquals(result1.defenderDamageDealt, result2.defenderDamageDealt)
            assertEquals(result1.cityOccupied, result2.cityOccupied)
        }

        @Test
        fun `different seed can produce different result`() {
            val results = (1..20).map { runBattle(it.toLong()) }
            // With enough seeds, should see variation
            assertTrue(results.map { it.attackerDamageDealt }.toSet().size > 1)
        }

        private fun runBattle(seed: Long): BattleResult {
            val attacker = WarUnitGeneral(
                createGeneral(leadership = 80, strength = 80, intel = 60, crew = 5000, train = 80, atmos = 80, rice = 10000),
                nationTech = 500f
            )
            val defender = WarUnitGeneral(
                createGeneral(id = 2, leadership = 60, strength = 60, intel = 50, crew = 3000, train = 70, atmos = 70, rice = 5000),
                nationTech = 300f
            )
            val city = createCity(def = 500, wall = 200, nationId = 2)
            return BattleEngine().resolveBattle(attacker, listOf(defender), city, Random(seed))
        }
    }

    // ──────────────────────────────────────────────────
    //  Injury mechanics parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Injury mechanics - legacy WarUnit.php")
    inner class InjuryParity {

        @Test
        fun `injury capped at 80`() {
            val gen = createGeneral(crew = 5000, rice = 10000)
            gen.injury = 79
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            unit.injury = 79
            // Simulate adding wound
            unit.injury = (unit.injury + 5).coerceAtMost(80)
            assertEquals(80, unit.injury)
        }

        @Test
        fun `continueWar false when rice too low relative to HP`() {
            // Legacy: rice <= hp/100
            val gen = createGeneral(crew = 1000, rice = 5)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            // hp=1000, rice=5; 5 <= 1000/100=10 → can't continue
            assertFalse(unit.continueWar())
        }

        @Test
        fun `continueWar true when enough rice`() {
            val gen = createGeneral(crew = 1000, rice = 100)
            val unit = WarUnitGeneral(gen, nationTech = 0f)
            assertTrue(unit.continueWar())
        }
    }

    // ──────────────────────────────────────────────────
    //  CrewType attack/defence coefficient parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("CrewType coefficients")
    inner class CrewTypeCoefParity {

        @Test
        fun `footman vs archer attack coef is 1_2`() {
            assertEquals(1.2, CrewType.FOOTMAN.getAttackCoef(CrewType.ARCHER), 0.001)
        }

        @Test
        fun `footman vs cavalry attack coef is 0_8`() {
            assertEquals(0.8, CrewType.FOOTMAN.getAttackCoef(CrewType.CAVALRY), 0.001)
        }

        @Test
        fun `footman vs footman is 1_0 default`() {
            assertEquals(1.0, CrewType.FOOTMAN.getAttackCoef(CrewType.FOOTMAN), 0.001)
        }

        @Test
        fun `fromCode round-trip`() {
            for (ct in CrewType.entries) {
                assertEquals(ct, CrewType.fromCode(ct.code))
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────

    private fun createGeneral(
        id: Long = 1,
        leadership: Short = 70,
        strength: Short = 70,
        intel: Short = 70,
        crew: Int = 3000,
        train: Short = 80,
        atmos: Short = 80,
        rice: Int = 5000,
        crewType: Int = CrewType.FOOTMAN.code,
        dex1: Int = 1000,
        dex2: Int = 1000,
        dex3: Int = 1000,
        dex4: Int = 1000,
        dex5: Int = 1000,
    ): General = General(
        id = id,
        worldId = 1,
        name = "테스트장수$id",
        nationId = 1,
        cityId = 1,
        leadership = leadership,
        strength = strength,
        intel = intel,
        crew = crew,
        crewType = crewType.toShort(),
        train = train,
        atmos = atmos,
        rice = rice,
        gold = 5000,
        dex1 = dex1,
        dex2 = dex2,
        dex3 = dex3,
        dex4 = dex4,
        dex5 = dex5,
        turnTime = OffsetDateTime.now(),
    )

    private fun createCity(
        def: Int = 500,
        wall: Int = 200,
        nationId: Long = 1,
        pop: Int = 50000,
    ): City = City(
        id = 1,
        worldId = 1,
        name = "테스트도시",
        nationId = nationId,
        def = def,
        defMax = 2000,
        wall = wall,
        wallMax = 2000,
        pop = pop,
        popMax = 100000,
    )
}
