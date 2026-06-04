package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.war.CheChulbyeong
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BO3 — port target = PHP `che_출병.php:134-261`.
 *
 * Pins:
 *  - ONE per-turn-rng choice draw `rng.choice(candidateCities)` (the ctx rng, NOT the warRng);
 *  - a friendly target → che_이동 alternative + NO battle;
 *  - warSeed built from the LOGGER year/month (`WarSeed.build`);
 *  - processWar invoked EXACTLY once (the OUTER wrapper, never processWarNG directly);
 *  - effects emitted as draft/result DELTAS (no inline DB);
 *  - the unique-item lottery fires AFTER the battle on the 출병 lineage;
 *  - CommandRegistry resolves che_출병 and does NOT register 급습/선전포고.
 */
class CheChulbyeongTest {

    private val pipeline = GeneralActionPipeline()
    private val seed = ByteArray(32) { it.toByte() }

    private fun general(id: Int = 1, cityId: Int = 1, nationId: Int = 1) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 10000,
        crew = 5000, train = 100.0, atmos = 100.0, crewTypeId = 1100,
        meta = linkedMapOf("explevel" to 0, "defence_train" to 0),
    )

    private fun city(id: Int, nationId: Int) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 1000, commerceMax = 9999,
        agriculture = 1000, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = 1000, securityMax = 9999,
        defense = 500, defenseMax = 9999,
        wall = 500, wallMax = 9999,
        population = 100000, populationMax = 999999,
    )

    private fun nation(id: Int, capital: Int) = Nation(id = id, level = 0, capitalCityId = capital, rice = 5000, tech = 0.0)

    /** A recording rng that captures the SEMANTIC draw stream (choice/nextBool). */
    private class RecordingRng(seed: ByteArray, val log: MutableList<String>) : RandUtil(LiteHashDrbg(seed)) {
        override fun <T> choice(items: List<T>): T { log.add("choice(${items.size})"); return super.choice(items) }
        override fun nextBool(prob: Double): Boolean { log.add("nextBool($prob)"); return super.nextBool(prob) }
    }

    private fun battleCtx(
        attackerCityId: Int = 1,
        finalTargetCityId: Int = 9,
        myNationId: Int = 1,
        distanceList: Map<Int, List<Pair<Int, Int>>> = linkedMapOf(0 to listOf(9 to 2)),
        defenders: Map<Int, List<General>> = emptyMap(),
        cities: Map<Int, City> = mapOf(1 to city(1, 1), 9 to city(9, 2)),
        nations: Map<Int, Nation> = mapOf(1 to nation(1, 1), 2 to nation(2, 9)),
    ) = BattleCommandContext(
        attackerCityId = attackerCityId,
        finalTargetCityId = finalTargetCityId,
        myNationId = myNationId,
        distanceList = distanceList,
        defenderGeneralsByCity = defenders,
        cityById = cities,
        nationById = nations,
        hiddenSeed = "0".repeat(32),
        loggerYear = 200,
        loggerMonth = 6,
    )

    private fun resolveCtx(rng: RandUtil, bctx: BattleCommandContext, g: General = general()) =
        GeneralActionResolveContext(
            draft = GeneralActionDraft(g, city(1, 1), nation(1, 1)),
            rng = rng,
            env = WorldEnv(year = 200, startYear = 180, develCost = 100),
            month = 6,
            date = "12:34",
            battleContext = bctx,
        )

    @Test
    fun `one choice draw on the per-turn rng then processWar invoked once`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        var pwCalls = 0
        val cmd = CheChulbyeong(
            pipeline,
            processWarFn = { _, _, _ ->
                pwCalls++
                ProcessWarResult(
                    conquerCity = false, defenderNationRice = null,
                    attackerCityDeadDelta = 0, defenderCityDeadDelta = 0,
                    attacker = dummyAttacker(rng), city = dummyCity(rng),
                    defenderCityTrainAtmosForTest = 0,
                )
            },
            lotteryFn = { _, _, _, _ -> false },
        )
        cmd.resolve(resolveCtx(rng, battleCtx()))

        assertEquals(1, log.count { it.startsWith("choice(") }, "exactly ONE choice draw: $log")
        assertEquals(1, pwCalls, "processWar invoked exactly once")
        // the choice draw must precede the lottery's nextBool (per-turn rng choice first).
        assertNull(cmd.lastAlternative, "an enemy target produces no alternative")
    }

    @Test
    fun `friendly target returns che_이동 alternative and runs NO battle`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        var pwCalls = 0
        // distanceList has ONLY my own cities → candidateCities falls back to my own → friendly target.
        val bctx = battleCtx(
            distanceList = linkedMapOf(0 to listOf(5 to 1)),   // city 5, my nation (1)
            cities = mapOf(1 to city(1, 1), 5 to city(5, 1), 9 to city(9, 2)),
        )
        val cmd = CheChulbyeong(
            pipeline,
            processWarFn = { _, _, _ -> pwCalls++; error("battle must not run") },
            lotteryFn = { _, _, _, _ -> false },
        )
        cmd.resolve(resolveCtx(rng, bctx))

        assertEquals("che_이동", cmd.lastAlternative, "friendly target swaps in the che_이동 alternative")
        assertEquals(0, pwCalls, "no battle runs on a friendly target")
        assertEquals(1, log.count { it.startsWith("choice(") }, "the choice draw still fires once: $log")
    }

    @Test
    fun `warSeed is built from the logger year and month`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        var capturedSeed: String? = null
        val cmd = CheChulbyeong(
            pipeline,
            processWarFn = { ws, _, _ ->
                capturedSeed = ws
                ProcessWarResult(false, null, 0, 0, dummyAttacker(rng), dummyCity(rng), 0)
            },
            lotteryFn = { _, _, _, _ -> false },
        )
        val bctx = battleCtx()
        // The chosen city is the single candidate (9). Expected seed = WarSeed.build(hidden, 200, 6, genId, 9).
        cmd.resolve(resolveCtx(rng, bctx, general(id = 42)))

        val expected = WarSeed.build("0".repeat(32), 200, 6, 42, 9)
        assertEquals(expected, capturedSeed, "warSeed must derive from logger year/month + genId + chosen city")
        assertEquals(expected, cmd.lastWarSeed)
    }

    @Test
    fun `effects are recorded as deltas and the lottery fires AFTER the battle on the chulbyeong lineage`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val order = mutableListOf<String>()
        var lotterySeedReason: String? = null
        val cmd = CheChulbyeong(
            pipeline,
            processWarFn = { _, _, _ ->
                order.add("processWar")
                ProcessWarResult(true, 6000, 100, 150, dummyAttacker(rng), dummyCity(rng), 0)
            },
            lotteryFn = { _, _, _, _ -> order.add("lottery"); false },
        )
        cmd.resolve(resolveCtx(rng, battleCtx()))

        assertEquals(listOf("processWar", "lottery"), order, "lottery must fire AFTER the battle")
        // conquerCity flag + deltas surfaced (no inline DB).
        assertTrue(cmd.lastConquerCity, "conquerCity flag propagated for B2")
        assertEquals(6000, cmd.lastBattleResult?.defenderNationRice)
        assertEquals(100, cmd.lastBattleResult?.attackerCityDeadDelta)
        assertEquals(150, cmd.lastBattleResult?.defenderCityDeadDelta)
    }

    @Test
    fun `CommandRegistry resolves che_출병 to CheChulbyeong (급습 now ported by C3)`() {
        val registry = CommandRegistry(pipeline)
        assertTrue(registry.resolve("che_출병") is CheChulbyeong, "che_출병 must resolve to CheChulbyeong")
        // 급습 is now registered by F4-C3 (cheGeupseup) — no longer a RestAction fallback.
        assertEquals("che_급습", registry.resolve("che_급습").key)
        // 선전포고 IS registered by FR2 (the AI-emitted def: argTest + FULL pack; resolve()-internals
        // are P6 / m10). It must NOT fall back to RestAction.
        assertEquals("che_선전포고", registry.resolve("che_선전포고").key)
    }

    // --- helpers: throwaway WarUnits so the FakeInner result carries valid (unused) state ---
    private fun dummyAttacker(rng: RandUtil): WarUnitGeneral = WarUnitGeneral(
        rng = rng, state = WarUnitGeneralState(general()), pipeline = pipeline,
        crewType = GameUnitConst.byId(1100)!!, tech = 0, isAttacker = true, cityLevel = 9, isCapital = false,
    )

    private fun dummyCity(rng: RandUtil): WarUnitCity = WarUnitCity(
        rng = rng, state = WarUnitCityState(city(9, 2)), year = 200, startYear = 180,
    )
}
