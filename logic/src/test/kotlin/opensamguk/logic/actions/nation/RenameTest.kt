package opensamguk.logic.actions.nation

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful test for che_국호변경 / che_국기변경 (che_국호변경.php:107-162, che_국기변경.php:95-144).
 *
 * Pins: aux-gate denial (ReqNationAuxValue can_*); RUNTIME dup-name fail (국호변경) — exact log + FALSE
 * WITHOUT consuming aux; success → aux=0, name/color set; exp/ded +5 (preReqTurn=0); inline-style logs.
 */
class RenameTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 7
    private val date = "15:00"

    private fun rng() = RandUtil(LiteHashDrbg(serializeSeed("0".repeat(32), "nationCommand", 195, MONTH, 1, "che_국호변경")))

    private fun actor() = General(
        id = 1, nationId = 2, cityId = 5,
        leadership = 90, strength = 80, intel = 70, injury = 0,
        experience = 600.0, dedication = 4000.0, officerLevel = 12, gold = 1000, rice = 1000,
        meta = linkedMapOf("explevel" to 6, "dedlevel" to 7),
    )

    private fun city() = City(5, 2, 7, 0,0,0,0, 1, 0, 50.0)

    private fun nation(can국호: Int = 1, can국기: Int = 1) = Nation(
        id = 2, level = 5, capitalCityId = 5, name = "위", color = "#FF0000",
        meta = linkedMapOf("aux" to linkedMapOf("can_국호변경" to can국호, "can_국기변경" to can국기)),
    )

    private val env = WorldEnv(year = 195, startYear = 184, develCost = 100)

    private fun ctx(d: GeneralActionDraft, args: Map<String, Any?>, dup: Boolean = false) =
        GeneralActionResolveContext(d, rng(), env, MONTH, date,
            generalName = "유비", args = args, runtimeNameDuplicate = dup)

    @Test
    fun `gukho aux gate denies when can_국호변경 not positive`() {
        val cmd = cheGukhoByeongyeong(pipeline)
        val cctx = ConstraintContext(actorId = 1, cityId = 5, nationId = 2,
            args = mapOf("nationName" to "촉"), env = mapOf("year" to 195, "startYear" to 184, "develCost" to 100),
            mode = ConstraintMode.FULL)
        val view = object : opensamguk.logic.constraints.StateView {
            override fun has(req: opensamguk.logic.constraints.RequirementKey) = true
            override fun get(req: opensamguk.logic.constraints.RequirementKey): Any? = when (req) {
                is opensamguk.logic.constraints.RequirementKey.General -> actor()
                is opensamguk.logic.constraints.RequirementKey.City -> city()
                is opensamguk.logic.constraints.RequirementKey.Nation -> nation(can국호 = 0)  // gate closed
                else -> null
            }
        }
        val results = cmd.buildConstraints(cctx).map { it.test(cctx, view) }
        assertTrue(results.any { it is ConstraintResult.Deny && it.reason == "더이상 변경이 불가능합니다." },
            "aux-gate denies with can_국호변경=0")
    }

    @Test
    fun `gukho runtime dup-name fails WITHOUT consuming aux, pushes exact log, returns nothing`() {
        val n = nation()
        val d = GeneralActionDraft(actor(), city(), n)
        val c = ctx(d, mapOf("nationName" to "촉"), dup = true)
        cheGukhoByeongyeong(pipeline).resolve(c)

        // exact runtime-fail log (actionName '국호변경'; the only log; MONTH-format via addLog)
        assertEquals(listOf("<C>●</>7월:이미 같은 국호를 가진 곳이 있습니다. 국호변경 실패 <1>15:00</>"), c.logs())
        // aux NOT consumed; name unchanged; no exp/ded grant
        assertEquals("위", d.nation!!.name, "name unchanged on dup fail")
        @Suppress("UNCHECKED_CAST")
        val aux = d.nation!!.meta["aux"] as Map<String, Any?>
        assertEquals(1, (aux["can_국호변경"] as Number).toInt(), "aux NOT consumed")
        assertEquals(600.0, d.general.experience, "no exp on fail")
        assertEquals(0, c.globalActionLogs().size, "no global log on fail")
    }

    @Test
    fun `gukho success sets name, consumes aux, grants exp_ded 5, inline logs`() {
        val n = nation()
        val d = GeneralActionDraft(actor(), city(), n)
        val c = ctx(d, mapOf("nationName" to "촉"), dup = false)
        cheGukhoByeongyeong(pipeline).resolve(c)

        assertEquals("촉", d.nation!!.name, "name set")
        @Suppress("UNCHECKED_CAST")
        val aux = d.nation!!.meta["aux"] as Map<String, Any?>
        assertEquals(0, (aux["can_국호변경"] as Number).toInt(), "aux consumed → 0")

        // general action log + global action log (촉 josa-로: 촉 ends in ㄱ → '으로')
        assertEquals("<C>●</>7월:국호를 <D><b>촉</b></>으로 변경합니다. <1>15:00</>", c.logs()[0])
        assertEquals(1, c.globalActionLogs().size)
        assertEquals("<C>●</>7월:<Y>유비</>가 국호를 <D><b>촉</b></>으로 변경합니다.", c.globalActionLogs()[0])

        // exp/ded += 5 (preReqTurn=0 → 5*(0+1)=5)
        assertEquals(605.0, d.general.experience, "exp += 5")
        assertEquals(4005.0, d.general.dedication, "ded += 5")
    }

    @Test
    fun `gukgi success sets color, consumes aux, grants exp_ded 5, inline color logs`() {
        // colorType index 7 → "#FFFF00" (GetNationColors list, 0-based)
        val n = nation()
        val d = GeneralActionDraft(actor(), city(), n)
        val c = ctx(d, mapOf("colorType" to 7))
        cheGukgiByeongyeong(pipeline).resolve(c)

        assertEquals("#FFFF00", d.nation!!.color, "color set to GetNationColors[7]")
        @Suppress("UNCHECKED_CAST")
        val aux = d.nation!!.meta["aux"] as Map<String, Any?>
        assertEquals(0, (aux["can_국기변경"] as Number).toInt(), "aux consumed")

        assertEquals("<C>●</>7월:<span style='color:#FFFF00;'><b>국기</b></span>를 변경하였습니다 <1>15:00</>", c.logs()[0])
        assertEquals(1, c.globalActionLogs().size)
        assertEquals("<C>●</>7월:<Y>유비</>가 <span style='color:#FFFF00;'><b>국기</b></span>를 변경하였습니다", c.globalActionLogs()[0])

        assertEquals(605.0, d.general.experience)
        assertEquals(4005.0, d.general.dedication)
    }
}
