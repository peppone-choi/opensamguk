package opensamguk.logic.actions.nation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.auxVar
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful resolve test for che_발령 / che_포상 (che_발령.php:134-174, che_포상.php:138-183).
 *
 * 발령: dest general's city → destCityID; last발령 on dest aux (yearMonth, +1 if different turn bucket);
 * dest-scope log + actor-scope log; ZERO exp/ded; setResultTurn(name,arg) (term NOT 0).
 * 포상: DOUBLE-CLAMP (argTest round(-2)+valueFit(100,10000); run() re-clamp valueFit(0, nation-reserve));
 * dest general resKey += amount; nation resKey -= amount; dest PLAIN log + actor log; ZERO exp/ded.
 */
class BallyeongPosangTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 5
    private val date = "09:00"

    private fun rng() = RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "nationCommand", 200, MONTH, 1, "che_발령")))

    private fun actor() = General(
        id = 1, nationId = 3, cityId = 10,
        leadership = 80, strength = 70, intel = 60, injury = 0,
        experience = 600.0, dedication = 4000.0, officerLevel = 12, gold = 1000, rice = 1000,
        meta = linkedMapOf("explevel" to 6, "dedlevel" to 7),
    )

    private fun dest() = General(
        id = 2, nationId = 3, cityId = 20,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 500, rice = 500,
        meta = linkedMapOf(),
    )

    private val nation = Nation(id = 3, level = 4, capitalCityId = 10, name = "위", gold = 100000, rice = 100000)
    private val env = WorldEnv(year = 200, startYear = 184, develCost = 100)

    private fun ballyeongCtx(d: GeneralActionDraft, diffBucket: Boolean = false) =
        GeneralActionResolveContext(d, rng(), env, MONTH, date,
            generalName = "조조", destGeneralName = "하후돈",
            args = mapOf("destGeneralID" to 2, "destCityID" to 30),
            destDifferentTurnBucket = diffBucket)

    @Test
    fun `ballyeong moves dest general city, writes last발령, dual logs, zero exp_ded`() {
        // dest city 30 = 계양; the actor reassigns dest general (city 2) to city 30
        val destCityId = 30
        val d = GeneralActionDraft(actor(), city = City(10, 3, 7, 0,0,0,0, 1, 0, 50.0), nation = nation)
        d.destGeneral = dest()
        d.destCity = City(destCityId, 3, 5, 0,0,0,0, 1, 0, 50.0)
        val c = ballyeongCtx(d)
        cheBallyeong(pipeline).resolve(c)

        // dest general moved to destCityID
        assertEquals(destCityId, d.destGeneral!!.cityId, "dest general city reassigned")
        // last발령 = joinYearMonth(200,5) = 200*12+5-1 = 2404 (same bucket → no +1)
        assertEquals(2404, (d.destGeneral!!.auxVar("last발령") as Number).toInt(), "last발령 yearMonth")

        // dest-scope log (계양, josa-로: 계양+로 → '으로')
        assertEquals(listOf("<Y>조조</>에 의해 <G><b>계양</b></>으로 발령됐습니다. <1>09:00</>"), c.logsTo(2))
        // actor-scope log (하후돈, josa-을: 돈+을 → '을')
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>5월:<Y>하후돈</>을 <G><b>계양</b></>으로 발령했습니다. <1>09:00</>", c.logs()[0])

        // ZERO exp/ded
        assertEquals(600.0, d.general.experience, "no exp grant")
        assertEquals(4000.0, d.general.dedication, "no ded grant")
    }

    @Test
    fun `ballyeong adds plus one to last발령 across turn buckets`() {
        val d = GeneralActionDraft(actor(), city = City(10, 3, 7, 0,0,0,0, 1, 0, 50.0), nation = nation)
        d.destGeneral = dest()
        d.destCity = City(30, 3, 5, 0,0,0,0, 1, 0, 50.0)
        cheBallyeong(pipeline).resolve(ballyeongCtx(d, diffBucket = true))
        assertEquals(2404 + 1, (d.destGeneral!!.auxVar("last발령") as Number).toInt(), "+1 across buckets")
    }

    private fun posangCtx(d: GeneralActionDraft, args: Map<String, Any?>) =
        GeneralActionResolveContext(d, rng(), env, MONTH, date,
            generalName = "조조", destGeneralName = "하후돈", args = args)

    @Test
    fun `posang argTest double-clamps amount round(-2)+valueFit(100,10000)`() {
        // amount 3333 → round(-2)=3300 → valueFit(100,10000)=3300
        val parsed = chePosang(pipeline).parseArgs(mapOf("isGold" to true, "amount" to 3333, "destGeneralID" to 2))
        assertEquals(3300, parsed["amount"], "argTest round(-2)+valueFit")
        // amount 50 → round(-2)=0 → valueFit lower-clamp 100; but PHP rejects amount<=0 BEFORE valueFit lower bound?
        // PHP: round(50,-2)=0(? actually 100 — round half-away). round(50,-2): 50/100=0.5 → 1 → 100. Pin it.
        assertEquals(100, chePosang(pipeline).parseArgs(
            mapOf("isGold" to false, "amount" to 50, "destGeneralID" to 2))["amount"])
        // 12345 → round(-2)=12300 → valueFit caps at 10000
        assertEquals(10000, chePosang(pipeline).parseArgs(
            mapOf("isGold" to true, "amount" to 12345, "destGeneralID" to 2))["amount"])
    }

    @Test
    fun `posang double-clamps gold, debits nation, dest PLAIN log + actor log, zero exp_ded`() {
        val args = mapOf("isGold" to true, "amount" to 3300, "destGeneralID" to 2)
        val d = GeneralActionDraft(actor(), city = City(10, 3, 7, 0,0,0,0, 1, 0, 50.0), nation = nation)
        d.destGeneral = dest()
        val c = posangCtx(d, args)
        chePosang(pipeline).resolve(c)

        // run() re-clamp: valueFit(3300, 0, nation.gold - basegold(0)) = 3300 (no further clamp)
        assertEquals(500 + 3300, d.destGeneral!!.gold, "dest gold += amount")
        assertEquals(100000 - 3300, d.nation!!.gold, "nation gold -= amount")

        // dest PLAIN log (no MONTH prefix; amount 3,300 josa-을: 300+을 → '을')
        assertEquals(listOf("<C>●</>금 <C>3,300</>을 포상으로 받았습니다."), c.plainLogsTo(2))
        // actor log (하후돈 josa-에게 fixed; amount comma)
        assertEquals(1, c.logs().size)
        assertEquals("<C>●</>5월:<Y>하후돈</>에게 금 <C>3,300</>을 수여했습니다. <1>09:00</>", c.logs()[0])

        assertEquals(600.0, d.general.experience, "no exp grant")
        assertEquals(4000.0, d.general.dedication, "no ded grant")
    }

    @Test
    fun `posang run-clamp caps amount at nation balance minus reserve (rice)`() {
        val lowNation = nation.copy(rice = 2500)  // reserve baserice=2000 → max payable 500
        val args = mapOf("isGold" to false, "amount" to 10000, "destGeneralID" to 2)
        val d = GeneralActionDraft(actor(), city = City(10, 3, 7, 0,0,0,0, 1, 0, 50.0), nation = lowNation)
        d.destGeneral = dest()
        val c = posangCtx(d, args)
        chePosang(pipeline).resolve(c)
        // run-clamp valueFit(10000, 0, 2500-2000=500) = 500
        assertEquals(500 + 500, d.destGeneral!!.rice, "dest rice += clamped 500")
        assertEquals(2500 - 500, d.nation!!.rice, "nation rice -= 500")
        assertTrue(c.plainLogsTo(2)[0].contains("쌀 <C>500</>"), "rice plain log clamped amount")
    }
}
