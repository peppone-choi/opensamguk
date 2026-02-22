package com.opensam.engine

import com.opensam.engine.war.BattleEngine
import com.opensam.engine.war.BattleResult
import com.opensam.engine.war.WarUnitGeneral
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import com.opensam.service.MapService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.OffsetDateTime

class FormulaParityTest {

    private lateinit var economyService: EconomyService
    private lateinit var battleEngine: BattleEngine

    @BeforeEach
    fun setUp() {
        economyService = EconomyService(
            mock(CityRepository::class.java),
            mock(NationRepository::class.java),
            mock(GeneralRepository::class.java),
            mock(MapService::class.java),
        )
        battleEngine = BattleEngine()
    }

    @Test
    fun `getDedLevel matches legacy ceil sqrt formula`() {
        assertEquals(1, invokeGetDedLevel(100))
        assertEquals(3, invokeGetDedLevel(900))
        assertEquals(10, invokeGetDedLevel(10000))
    }

    @Test
    fun `calcCityGoldIncome matches legacy formula sample`() {
        val city = createCity(pop = 10000, comm = 500, commMax = 1000, trust = 80f, secu = 500, secuMax = 1000)

        val income = invokeCalcCityGoldIncome(city, officerCnt = 0, isCapital = false, nationLevel = 1)

        assertEquals(157.5, income, 0.0001)
    }

    @Test
    fun `calcCityRiceIncome matches legacy formula sample`() {
        val city = createCity(pop = 10000, agri = 500, agriMax = 1000, trust = 80f, secu = 500, secuMax = 1000)

        val income = invokeCalcCityRiceIncome(city, officerCnt = 0, isCapital = false, nationLevel = 1)

        assertEquals(157.5, income, 0.0001)
    }

    @Test
    fun `resolveBattle is deterministic with same LiteHashDRBG seed`() {
        val left = runBattle("battle_test")
        val right = runBattle("battle_test")

        assertEquals(left.attackerDamageDealt, right.attackerDamageDealt)
        assertEquals(left.defenderDamageDealt, right.defenderDamageDealt)
        assertEquals(left.attackerWon, right.attackerWon)
        assertEquals(left.cityOccupied, right.cityOccupied)
    }

    @Test
    fun `resolveBattle differs with different LiteHashDRBG seeds`() {
        val baseline = runBattle("battle_test")
        val alternatives = listOf("battle_test_alt_1", "battle_test_alt_2", "battle_test_alt_3")
            .map { runBattle(it) }

        assertTrue(alternatives.any { isBattleOutcomeDifferent(baseline, it) })
    }

    private fun runBattle(seed: String): BattleResult {
        val attackerGeneral = createGeneral(
            id = 1,
            nationId = 1,
            leadership = 75,
            strength = 78,
            intel = 60,
            crew = 3500,
            train = 78,
            atmos = 77,
            rice = 120000,
            experience = 3000,
            dedication = 2000,
        )
        val defenderGeneral = createGeneral(
            id = 2,
            nationId = 2,
            leadership = 70,
            strength = 72,
            intel = 65,
            crew = 3200,
            train = 75,
            atmos = 74,
            rice = 100000,
            experience = 2500,
            dedication = 1800,
        )
        val city = createCity(nationId = 2, def = 450, wall = 500, pop = 20000)

        return battleEngine.resolveBattle(
            attacker = WarUnitGeneral(attackerGeneral),
            defenders = listOf(WarUnitGeneral(defenderGeneral)),
            city = city,
            rng = LiteHashDRBG.build(seed),
        )
    }

    private fun isBattleOutcomeDifferent(left: BattleResult, right: BattleResult): Boolean {
        return left.attackerDamageDealt != right.attackerDamageDealt ||
            left.defenderDamageDealt != right.defenderDamageDealt ||
            left.attackerWon != right.attackerWon ||
            left.cityOccupied != right.cityOccupied
    }

    private fun invokeGetDedLevel(dedication: Int): Int {
        val method = EconomyService::class.java.getDeclaredMethod(
            "getDedLevel",
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(economyService, dedication) as Int
    }

    private fun invokeCalcCityGoldIncome(city: City, officerCnt: Int, isCapital: Boolean, nationLevel: Int): Double {
        val method = EconomyService::class.java.getDeclaredMethod(
            "calcCityGoldIncome",
            City::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(economyService, city, officerCnt, isCapital, nationLevel) as Double
    }

    private fun invokeCalcCityRiceIncome(city: City, officerCnt: Int, isCapital: Boolean, nationLevel: Int): Double {
        val method = EconomyService::class.java.getDeclaredMethod(
            "calcCityRiceIncome",
            City::class.java,
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(economyService, city, officerCnt, isCapital, nationLevel) as Double
    }

    private fun createGeneral(
        id: Long,
        nationId: Long,
        leadership: Short,
        strength: Short,
        intel: Short,
        crew: Int,
        train: Short,
        atmos: Short,
        rice: Int,
        experience: Int,
        dedication: Int,
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
            rice = rice,
            experience = experience,
            dedication = dedication,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        nationId: Long = 1,
        pop: Int = 10000,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        secu: Int = 500,
        secuMax: Int = 1000,
        trust: Float = 80f,
        def: Int = 500,
        wall: Int = 500,
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            pop = pop,
            popMax = 50000,
            agri = agri,
            agriMax = agriMax,
            comm = comm,
            commMax = commMax,
            secu = secu,
            secuMax = secuMax,
            trust = trust,
            def = def,
            defMax = 1000,
            wall = wall,
            wallMax = 1000,
        )
    }
}
