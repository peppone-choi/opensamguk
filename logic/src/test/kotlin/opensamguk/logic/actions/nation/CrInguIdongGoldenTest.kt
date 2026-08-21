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
import opensamguk.logic.domestic.getExpLevel
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Port-faithful unit GoldenTest for cr_인구이동 (cr_인구이동.php:139-186) — deterministic, draw_count=0.
 *
 * 로그 문자열은 PHP run()(cr_인구이동.php:180)에서 verbatim 복사:
 *   "<G><b>{dest}</b></>{josa-로} 인구 <C>{amount}</>명을 옮겼습니다. <1>{date}</>"
 * docker 캡처 불필요 — effect 델타(src/dest pop, nation gold/rice, actor exp/ded)와 로그 바이트를 직접 단언.
 */
class CrInguIdongGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 4
    private val date = "10:10"

    /** 0-draw 단언용 RNG: 어떤 draw 메서드든 호출되면 즉시 fail (패러티: 인구이동은 RNG 미사용). */
    private class NoRng : RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "x", 0, 0, 0, "x"))) {
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int =
            fail("cr_인구이동 must draw 0 times — nextRangeInt called")
        override fun nextBool(prob: Double): Boolean =
            fail("cr_인구이동 must draw 0 times — nextBool called")
    }

    private fun actor() = General(
        id = 1, nationId = 2, cityId = 2,  // src = 허창(2)
        leadership = 90, strength = 80, intel = 70, injury = 0,
        experience = 1100.0, dedication = 4000.0, officerLevel = 12, gold = 1000, rice = 1000,
        meta = linkedMapOf("explevel" to getExpLevel(1100.0), "dedlevel" to 7),
    )

    /** City(id, nationId, level, comm, commMax, agri, agriMax, supply, front, trust, ...named). */
    private fun srcCity(pop: Int) = City(
        2, 2, 7, 0, 0, 0, 0, 1, 0, 50.0, population = pop, populationMax = 200_000,
    )
    private fun destCity(pop: Int) = City(
        1, 2, 6, 0, 0, 0, 0, 1, 0, 50.0, population = pop, populationMax = 200_000,  // dest = 업(1)
    )

    private fun nation() = Nation(
        id = 2, level = 5, capitalCityId = 2, name = "위", color = "#FF0000",
        gold = 1_000_000, rice = 1_000_000,
    )

    private val env = WorldEnv(year = 198, startYear = 184, develCost = 100)

    @Test
    fun `population move debits src adds dest, costs gold rice, exp ded plus 5, byte log, zero draws`() {
        val amount = 50_000
        val srcPop = 150_000   // src.pop - minAvailableRecruitPop(30000) = 120000 >= amount → no run-clamp
        val destPop = 80_000
        val n = nation()
        val d = GeneralActionDraft(actor(), srcCity(srcPop), n)
        d.destCity = destCity(destPop)
        val c = GeneralActionResolveContext(
            d, NoRng(), env, MONTH, date,
            generalName = "조조",
            args = mapOf("destCityID" to 1, "amount" to amount),
        )
        crInguIdong(pipeline).resolve(c)

        // cost = Util::round(develcost * amount / 10000) = round(100 * 50000 / 10000) = round(500.0) = 500
        val cost = phpRound(100.0 * amount / 10000.0)
        assertEquals(500, cost, "cost (gold==rice)")

        // src.pop -= amount; dest.pop += amount
        assertEquals(srcPop - amount, d.city.population, "src pop debited")
        assertEquals(destPop + amount, d.destCity!!.population, "dest pop credited")

        // nation gold/rice -= cost
        assertEquals(1_000_000 - cost, d.nation!!.gold, "nation gold debited")
        assertEquals(1_000_000 - cost, d.nation!!.rice, "nation rice debited")

        // exp/ded += 5 (literal)
        assertEquals(1105.0, d.general.experience, "exp += 5")
        assertEquals(4005.0, d.general.dedication, "ded += 5")

        // single actor action-log line, no global broadcast. 업 ends in ㅂ → josa '으로'.
        assertEquals(
            "<C>●</>4월:<G><b>업</b></>으로 인구 <C>50000</>명을 옮겼습니다. <1>10:10</>",
            c.logs()[0], "[인구이동] action-log byte-match",
        )
        assertEquals(1, c.logs().size, "exactly one action-log line")
        assertEquals(0, c.globalActionLogs().size, "no global broadcast")
    }

    @Test
    fun `amount is run-clamped to src pop minus minAvailableRecruitPop, but cost uses requested amount`() {
        // src.pop = 40000 → cap = 40000 - 30000 = 10000. 요청 amount=50000 → 이동 인구는 10000으로 clamp.
        // 단 cost는 요청량(50000) 기준 — cr_인구이동.php:158(이동량 clamp) vs :174(getCost는 arg.amount).
        val srcPop = 40_000
        val destPop = 80_000
        val argAmount = 50_000
        val cap = srcPop - GameConst.minAvailableRecruitPop  // 10000
        val n = nation()
        val d = GeneralActionDraft(actor(), srcCity(srcPop), n)
        d.destCity = destCity(destPop)
        val c = GeneralActionResolveContext(
            d, NoRng(), env, MONTH, date,
            generalName = "조조",
            args = mapOf("destCityID" to 1, "amount" to argAmount),
        )
        crInguIdong(pipeline).resolve(c)

        // 이동 인구 = clamp(50000, null, 10000) = 10000
        assertEquals(srcPop - cap, d.city.population, "src clamped to minAvailableRecruitPop")
        assertEquals(destPop + cap, d.destCity!!.population, "dest receives clamped amount")

        // cost는 요청량(50000) 기준: round(100 * 50000 / 10000) = 500
        val cost = phpRound(100.0 * argAmount / 10000.0)
        assertEquals(1_000_000 - cost, d.nation!!.gold, "cost uses requested amount, not clamped")

        // 로그의 <C>{amount}</> 는 이동된(clamped) 인구 = 10000
        assertEquals(
            "<C>●</>4월:<G><b>업</b></>으로 인구 <C>$cap</>명을 옮겼습니다. <1>10:10</>",
            c.logs()[0], "[인구이동] log uses clamped move amount",
        )
    }

    @Test
    fun `argTest clamps to AMOUNT_LIMIT and rejects negative`() {
        val cmd = crInguIdong(pipeline)
        // AMOUNT_LIMIT = 100000 절단
        assertEquals(100_000, (cmd.argTest(mapOf("destCityID" to 1, "amount" to 250_000))!!["amount"] as Int),
            "amount clamped to AMOUNT_LIMIT")
        // 음수 reject
        assertEquals(null, cmd.argTest(mapOf("destCityID" to 1, "amount" to -5)), "negative amount rejected")
        assertEquals(
            999_999,
            cmd.argTest(mapOf("destCityID" to 999_999, "amount" to 100))!!["destCityID"],
            "city membership is validated by the active-map FULL constraint",
        )
        // numeric string 허용 (is_numeric)
        assertEquals(100, (cmd.argTest(mapOf("destCityID" to 1, "amount" to "100"))!!["amount"] as Int),
            "numeric string amount accepted")
    }

    @Test
    fun `getCost is develcost times amount over 10000, half-away`() {
        val cmd = crInguIdong(pipeline)
        assertEquals(500, cmd.getCost(100, 50_000), "100*50000/10000 = 500")
        // half-away: 100 * 5500 / 10000 = 55.0 → 55; 100 * 5550 / 10000 = 55.5 → 56 (half-away)
        assertEquals(55, cmd.getCost(100, 5_500))
        assertEquals(56, cmd.getCost(100, 5_550), "half-away from zero (NOT half-even)")
    }
}
