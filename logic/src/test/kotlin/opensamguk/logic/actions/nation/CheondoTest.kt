package opensamguk.logic.actions.nation

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful test for che_천도 / che_무작위수도이전 (che_천도.php:99-235, che_무작위수도이전.php:78-166).
 *
 * 천도: variable cost (develcost*5 * 2^dist) + preReqTurn (dist*2); run() does NOT debit gold/rice
 * (cost is precondition only); nation.capital → dest + capset+1; exp/ded variable; inline 천도 logs.
 * 무작위수도이전: rng->choice the ONLY RNG; multi-general move; aux can_무작위수도이전 -= 1; new/old city
 * nation flips; nation.capital → dest.
 */
class CheondoTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 4
    private val date = "10:10"

    @AfterTest
    fun clearStaticEventHandlers() = StaticEventHandler.clear()

    private fun cheondoRng() =
        RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "nationCommand", 198, MONTH, 1, "che_천도")))
    private fun moveRng() =
        RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "nationCommand", 198, MONTH, 1, "che_무작위수도이전")))

    private fun actor() = General(
        id = 1, nationId = 2, cityId = 5,
        leadership = 90, strength = 80, intel = 70, injury = 0,
        experience = 1100.0, dedication = 4000.0, officerLevel = 12, gold = 1000, rice = 1000,
        meta = linkedMapOf("explevel" to getExp(1100.0), "dedlevel" to 7),
    )

    private fun getExp(e: Double) = opensamguk.logic.domestic.getExpLevel(e)

    private fun city() = City(5, 2, 7, 0,0,0,0, 1, 0, 50.0)

    private fun nation() = Nation(
        id = 2, level = 5, capitalCityId = 5, name = "위", color = "#FF0000",
        gold = 1_000_000, rice = 1_000_000, capset = 3,
        meta = linkedMapOf("aux" to linkedMapOf("can_무작위수도이전" to 2)),
    )

    private val env = WorldEnv(year = 198, startYear = 184, develCost = 100)

    @Test
    fun `cheondo moves capital, bumps capset, no gold rice debit, variable exp_ded, inline logs`() {
        // distance 3 → preReqTurn = 6 → exp/ded = 5*(6+1) = 35; cost = 100*5 * 2^3 = 4000 (NOT debited)
        val dist = 3
        val destCityId = 30  // 계양
        val n = nation()
        val d = GeneralActionDraft(actor(), city(), n)
        d.destCity = City(destCityId, 2, 6, 0,0,0,0, 1, 0, 50.0)
        val c = GeneralActionResolveContext(d, cheondoRng(), env, MONTH, date,
            generalName = "조조", args = mapOf("destCityID" to destCityId), cityDistance = dist)
        cheCheondo(pipeline).resolve(c)

        // nation.capital → dest; capset+1; gold/rice UNCHANGED
        assertEquals(destCityId, d.nation!!.capitalCityId, "capital moved")
        assertEquals(4, d.nation!!.capset, "capset bump")
        assertEquals(1_000_000, d.nation!!.gold, "gold NOT debited")
        assertEquals(1_000_000, d.nation!!.rice, "rice NOT debited")

        // exp/ded += 35 (5*(dist*2 + 1)); start exp 1100 → 1135
        assertEquals(1135.0, d.general.experience, "exp += 35")
        assertEquals(4035.0, d.general.dedication, "ded += 35")

        // logs (계양 josa-로: 양 ends in ㅇ → '으로')
        assertEquals(
            listOf(
                "general:action:<C>●</>4월:<G><b>계양</b></>으로 천도했습니다. <1>10:10</>",
                "general:history:<C>●</>198년 4월:<G><b>계양</b></>으로 <M>천도</>명령",
                "nation:history:<C>●</>198년 4월:<Y>조조</>가 <G><b>계양</b></>으로 <M>천도</> 명령",
                "global:action:<C>●</>4월:<Y>조조</>가 <G><b>계양</b></>으로 <M>천도</>를 명령하였습니다.",
                "global:history:<C>●</>198년 4월:<S><b>【천도】</b></><D><b>위</b></>가 <G><b>계양</b></>으로 <M>천도</>하였습니다.",
            ),
            c.orderedLogEvents().map { "${it.scope}:${it.category}:${it.text}" },
        )
    }

    @Test
    fun `cheondo cost and preReqTurn track distance`() {
        val cmd = cheCheondo(pipeline)
        // cost = develcost*5 * 2^dist
        assertEquals(500L, cmd.getCost(env.develCost, 0), "dist 0 → cost 500")
        assertEquals(4000L, cmd.getCost(env.develCost, 3), "dist 3 → cost 4000")
        // preReqTurn = dist*2
        assertEquals(0, cmd.getPreReqTurnForDistance(0))
        assertEquals(6, cmd.getPreReqTurnForDistance(3))
        // distance fallback ?? 50
        assertEquals(505, cmd.expDedForDistance(null), "null dist → 50*2 preReqTurn → 5*(100+1)=505")
    }

    @Test
    fun `cheondo cost keeps PHP powers of two above the Int shift width`() {
        val cmd = cheCheondo(pipeline)

        assertEquals(167_772_160L, cmd.getCost(1, 25))
        assertEquals(10_737_418_240L, cmd.getCost(1, 31))
        assertEquals(21_474_836_480L, cmd.getCost(1, 32))
        assertEquals(5_629_499_534_213_120L, cmd.getCost(1, 50))
        assertEquals(Long.MAX_VALUE, cmd.getCost(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `cheondo resolve defers the static event to the daemon post drain`() {
        val observed = mutableListOf<String>()
        StaticEventHandler.register("che_천도") { general, destGeneral, env, params ->
            val month = env["month"]
            val destCity = params["destCityID"]
            observed += "${general.id}:${destGeneral == null}:$month:$destCity"
        }
        val destCityId = 30
        val draft = GeneralActionDraft(actor(), city(), nation()).also {
            it.destCity = City(destCityId, 2, 6, 0, 0, 0, 0, 1, 0, 50.0)
        }
        val context = GeneralActionResolveContext(
            draft,
            cheondoRng(),
            env,
            MONTH,
            date,
            generalName = "조조",
            args = mapOf("destCityID" to destCityId),
            cityDistance = 3,
        )

        cheCheondo(pipeline).resolve(context)

        assertEquals(emptyList(), observed)
    }

    @Test
    fun `mujakwi moves all generals to a chosen neutral city, flips ownership, decrements aux`() {
        val n = nation()
        val d = GeneralActionDraft(actor(), city(), n)
        // cascade: two other own generals also move
        d.cascadeGenerals.add(General(2, 2, 5, 50,50,50,0, 0.0,0.0, 1, 0, 0))
        d.cascadeGenerals.add(General(3, 2, 5, 60,60,60,0, 0.0,0.0, 1, 0, 0))
        val candidates = listOf(30)  // single neutral candidate → deterministic choice
        val c = GeneralActionResolveContext(d, moveRng(), env, MONTH, date,
            generalName = "조조", args = emptyMap(), candidateCityIds = candidates)
        cheMujakwiSudoIjeon(pipeline).resolve(c)

        val destId = 30
        // actor + cascade generals all moved to dest
        assertEquals(destId, d.general.cityId, "actor moved")
        assertTrue(d.cascadeGenerals.all { it.cityId == destId }, "cascade generals moved")
        // nation.capital → dest
        assertEquals(destId, d.nation!!.capitalCityId, "capital moved")
        // aux can_무작위수도이전 2 → 1
        @Suppress("UNCHECKED_CAST")
        val aux = d.nation!!.meta["aux"] as Map<String, Any?>
        assertEquals(1, (aux["can_무작위수도이전"] as Number).toInt(), "aux decremented")
        // dest city now owned, old capital released (cascadeCities carries both)
        assertTrue(d.cascadeCities.any { it.id == destId && it.nationId == 2 }, "dest city -> nation")
        assertTrue(d.cascadeCities.any { it.id == 5 && it.nationId == 0 }, "old capital -> neutral")

        // exp/ded += 10 (preReqTurn=1 → 5*(1+1))
        assertEquals(1110.0, d.general.experience, "exp += 10")
        assertEquals(4010.0, d.general.dedication, "ded += 10")
    }

    @Test
    fun `mujakwi resolves a Han-only capital without partial mutation`() {
        val draft = GeneralActionDraft(actor(), city(), nation())
        val context = GeneralActionResolveContext(
            draft,
            moveRng(),
            env.copy(mapName = "han"),
            MONTH,
            date,
            generalName = "조조",
            args = emptyMap(),
            candidateCityIds = listOf(419),
        )

        // id 419 는 형주 「석」(析) 이다 — han.json 에 이름이 같은 「석」이 하나 더 있다(id 595,
        // 익주, 锡). 재번호매김이 419 를 다른 城으로 옮기면 이 단언이 먼저 빨개진다.
        assertEquals("석", CityConstRegistry.of("han").byId(419)!!.name)
        assertEquals(CityConstRegistry.of("han").regionIdByName("형주"), CityConstRegistry.of("han").byId(419)!!.region)

        cheMujakwiSudoIjeon(pipeline).resolve(context)

        assertEquals(419, draft.general.cityId)
        assertEquals(419, draft.nation!!.capitalCityId)
        assertTrue(draft.cascadeCities.any { it.id == 419 && it.nationId == 2 })
        assertTrue(context.logs().any { it.contains("<b>석</b>") })
    }
}
