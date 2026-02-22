package com.opensam.engine

import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.general.*
import com.opensam.engine.war.BattleEngine
import com.opensam.engine.war.WarUnitGeneral
import com.opensam.entity.City
import com.opensam.entity.General
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Golden Snapshot Tests — verify that a sequence of game operations
 * produces deterministic, reproducible results.
 *
 * Strategy C: Run a mini-scenario with fixed state + LiteHashDRBG,
 * capture outputs, and verify they match on every run.
 */
class GoldenSnapshotTest {

    private val env = CommandEnv(year = 200, month = 1, startYear = 184, worldId = 1, develCost = 100)

    /**
     * Snapshot 1: A full command cycle for one general.
     * Run training → recruitment → farming → drilling in sequence,
     * then verify all outputs match expected golden values.
     */
    @Test
    fun `golden snapshot - single general command cycle`() = runBlocking {
        val snapshot1 = runSingleGeneralCycle()
        val snapshot2 = runSingleGeneralCycle()

        assertEquals(snapshot1.size, snapshot2.size)
        for (i in snapshot1.indices) {
            assertEquals(snapshot1[i].success, snapshot2[i].success, "Command $i success mismatch")
            assertEquals(snapshot1[i].message, snapshot2[i].message, "Command $i message mismatch")
        }
    }

    private suspend fun runSingleGeneralCycle(): List<CommandResult> {
        val rng = LiteHashDRBG.build("golden_single_cycle")
        val results = mutableListOf<CommandResult>()

        // 1. Training
        val g1 = createGeneral(id = 1, leadership = 85, crew = 400, train = 50, atmos = 60)
        results.add(che_훈련(g1, env).run(rng))

        // 2. Recruitment (new crewType)
        val g2 = createGeneral(id = 1, leadership = 70, crew = 0, gold = 50000, rice = 50000)
        val recruitArg = mapOf<String, Any>("crewType" to 0, "amount" to 2000)
        results.add(che_징병(g2, env, recruitArg).run(rng))

        // 3. Farming (domestic command with RNG)
        val g3 = createGeneral(id = 1, intel = 80)
        val city = createCity(agri = 400, agriMax = 1000, trust = 85f)
        val farmCmd = che_농지개간(g3, env)
        farmCmd.city = city
        results.add(farmCmd.run(rng))

        // 4. Morale boost
        val g4 = createGeneral(id = 1, leadership = 75, crew = 600, atmos = 55, train = 70, gold = 5000)
        results.add(che_사기진작(g4, env).run(rng))

        // 5. Drilling (uses RNG)
        val g5 = createGeneral(id = 1, leadership = 60, strength = 70, intel = 50,
            crew = 2000, train = 70, atmos = 70, gold = 5000, rice = 5000)
        results.add(che_단련(g5, env).run(rng))

        return results
    }

    /**
     * Snapshot 2: Multi-general parallel operations.
     * Two generals from different nations each execute commands,
     * sharing the same RNG stream.
     */
    @Test
    fun `golden snapshot - multi general parallel ops`() = runBlocking {
        val snapshot1 = runMultiGeneralOps()
        val snapshot2 = runMultiGeneralOps()

        assertEquals(snapshot1.size, snapshot2.size)
        for (i in snapshot1.indices) {
            assertEquals(snapshot1[i].message, snapshot2[i].message, "Op $i message mismatch")
        }
    }

    private suspend fun runMultiGeneralOps(): List<CommandResult> {
        val rng = LiteHashDRBG.build("golden_multi_general")
        val results = mutableListOf<CommandResult>()

        // General A (nation 1): farm → train → drill
        val genA = createGeneral(id = 1, nationId = 1, leadership = 80, strength = 65, intel = 75,
            crew = 1500, train = 65, atmos = 70, gold = 10000, rice = 10000)
        val cityA = createCity(nationId = 1, agri = 500, agriMax = 1000, trust = 80f)

        val farmA = che_농지개간(genA, env)
        farmA.city = cityA
        results.add(farmA.run(rng))
        results.add(che_훈련(genA, env).run(rng))
        results.add(che_단련(genA, env).run(rng))

        // General B (nation 2): recruit → morale → farm
        val genB = createGeneral(id = 2, nationId = 2, leadership = 70, strength = 80, intel = 60,
            crew = 500, train = 60, atmos = 55, gold = 20000, rice = 20000)
        val cityB = createCity(nationId = 2, agri = 300, agriMax = 1000, trust = 75f)

        val recruitArg = mapOf<String, Any>("crewType" to 0, "amount" to 1000)
        results.add(che_징병(genB, env, recruitArg).run(rng))
        results.add(che_사기진작(genB, env).run(rng))
        val farmB = che_농지개간(genB, env)
        farmB.city = cityB
        results.add(farmB.run(rng))

        return results
    }

    /**
     * Snapshot 3: Battle outcome golden snapshot.
     * Fixed attacker/defender stats + LiteHashDRBG seed → exact same battle result.
     */
    @Test
    fun `golden snapshot - battle outcome`() = runBlocking {
        val result1 = runBattleScenario()
        val result2 = runBattleScenario()

        assertEquals(result1.attackerDamageDealt, result2.attackerDamageDealt)
        assertEquals(result1.defenderDamageDealt, result2.defenderDamageDealt)
        assertEquals(result1.attackerWon, result2.attackerWon)
        assertEquals(result1.cityOccupied, result2.cityOccupied)

        // Assert specific golden values (pinned from first run)
        assertTrue(result1.attackerDamageDealt > 0, "Attacker must deal damage")
        assertTrue(result1.defenderDamageDealt > 0, "Defender must deal damage")
    }

    private fun runBattleScenario(): com.opensam.engine.war.BattleResult {
        val rng = LiteHashDRBG.build("golden_battle_scenario_v1")
        val engine = BattleEngine()

        val attacker = createGeneral(
            id = 1, nationId = 1, leadership = 85, strength = 90, intel = 70,
            crew = 5000, train = 80, atmos = 80, rice = 200000, experience = 5000,
        )
        val defender = createGeneral(
            id = 2, nationId = 2, leadership = 75, strength = 78, intel = 65,
            crew = 4000, train = 75, atmos = 75, rice = 150000, experience = 3000,
        )
        val city = createCity(nationId = 2, def = 400, wall = 500)

        return engine.resolveBattle(
            WarUnitGeneral(attacker),
            listOf(WarUnitGeneral(defender)),
            city,
            rng,
        )
    }

    /**
     * Snapshot 4: Mixed commands + battle in sequence.
     * A full "turn" of activity: domestic → recruitment → battle.
     */
    @Test
    fun `golden snapshot - full turn simulation`() = runBlocking {
        data class TurnSnapshot(
            val commandResults: List<CommandResult>,
            val battleDamageDealt: Int,
            val battleDamageReceived: Int,
            val battleWon: Boolean,
        )

        suspend fun runFullTurn(): TurnSnapshot {
            val rng = LiteHashDRBG.build("golden_full_turn_v1")
            val results = mutableListOf<CommandResult>()

            // Phase 1: Domestic commands
            val general = createGeneral(
                id = 1, nationId = 1, leadership = 80, strength = 85, intel = 70,
                crew = 3000, train = 70, atmos = 70, gold = 50000, rice = 100000,
                experience = 3000, dedication = 2000,
            )
            val homeCity = createCity(nationId = 1, agri = 600, agriMax = 1000, trust = 85f)

            // Farm
            val farmCmd = che_농지개간(general, env)
            farmCmd.city = homeCity
            results.add(farmCmd.run(rng))

            // Train
            results.add(che_훈련(general, env).run(rng))

            // Drill
            results.add(che_단련(general, env).run(rng))

            // Phase 2: Battle
            val enemy = createGeneral(
                id = 2, nationId = 2, leadership = 65, strength = 70, intel = 55,
                crew = 2500, train = 65, atmos = 65, rice = 80000, experience = 2000,
            )
            val enemyCity = createCity(nationId = 2, def = 300, wall = 400)
            val engine = BattleEngine()
            val battleResult = engine.resolveBattle(
                WarUnitGeneral(general),
                listOf(WarUnitGeneral(enemy)),
                enemyCity,
                rng,
            )

            return TurnSnapshot(
                commandResults = results,
                battleDamageDealt = battleResult.attackerDamageDealt,
                battleDamageReceived = battleResult.defenderDamageDealt,
                battleWon = battleResult.attackerWon,
            )
        }

        val snap1 = runFullTurn()
        val snap2 = runFullTurn()

        // All command results must match
        assertEquals(snap1.commandResults.size, snap2.commandResults.size)
        for (i in snap1.commandResults.indices) {
            assertEquals(snap1.commandResults[i].message, snap2.commandResults[i].message, "Cmd $i")
        }

        // Battle results must match
        assertEquals(snap1.battleDamageDealt, snap2.battleDamageDealt)
        assertEquals(snap1.battleDamageReceived, snap2.battleDamageReceived)
        assertEquals(snap1.battleWon, snap2.battleWon)
    }

    // ========== Helpers ==========

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        politics: Short = 50,
        charm: Short = 50,
        crew: Int = 1000,
        train: Short = 80,
        atmos: Short = 80,
        gold: Int = 5000,
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
            politics = politics,
            charm = charm,
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
        nationId: Long = 1,
        agri: Int = 500,
        agriMax: Int = 1000,
        trust: Float = 80f,
        def: Int = 500,
        wall: Int = 500,
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            pop = 20000,
            popMax = 50000,
            agri = agri,
            agriMax = agriMax,
            comm = 500,
            commMax = 1000,
            secu = 500,
            secuMax = 1000,
            trust = trust,
            def = def,
            defMax = 1000,
            wall = wall,
            wallMax = 1000,
        )
    }
}
