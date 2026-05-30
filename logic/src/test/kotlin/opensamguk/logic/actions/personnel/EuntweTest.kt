package opensamguk.logic.actions.personnel

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.domain.rebirth
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful test for che_은퇴 (`che_은퇴.php`) + `General::rebirth()` (`General.php:602-639`).
 *
 *   - reqAge=60 constraint (ReqGeneralValue('age','나이','>=',60,...)) reads meta['age'].
 *   - getPreReqTurn()=1 → a 2-turn command.
 *   - if env.isunited==0 → CheckHall BEFORE rebirth (succession subsystem → guarded stub, no-op in P2).
 *   - rebirth: leadership/strength/intel *= 0.85 floor10; injury=0; experience/dedication *= 0.5;
 *     age=20; specage/specage2=0; dex1..5 *= 0.5; all RankColumn → 0 (succession seam). NO inheritance bump.
 *   - action logs: rebirth's PLAIN "나이가 들어 <R>은퇴</>하고 자손에게 자리를 물려줍니다." then the
 *     command's MONTH "은퇴하였습니다. <1>date</>"; global: rebirth's "<Y>{name}</>... <R>은퇴</>...".
 */
class EuntweTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 6
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 240, MONTH, 42, "che_은퇴")))
    private val env = WorldEnv(year = 240, startYear = 184, develCost = 120)
    private val date = "23:00"

    private fun elder(age: Int = 65) = General(
        id = 42, nationId = 7, cityId = 500,
        leadership = 70, strength = 64, intel = 12, injury = 30,
        experience = 1000.0, dedication = 2000.0,
        officerLevel = 5, gold = 1000, rice = 1000,
        meta = linkedMapOf(
            "explevel" to 10, "dedlevel" to 5, "age" to age, "name" to "황충",
            "dex1" to 1000, "dex2" to 2000, "specage" to 3, "specage2" to 4,
        ),
    )

    private fun city() = City(
        id = 500, nationId = 7, level = 5,
        commerce = 1, commerceMax = 1, agriculture = 1, agricultureMax = 1,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private val nation = Nation(id = 7, level = 3, capitalCityId = 500, name = "촉")

    // -------- rebirth math (logic/domain helper) --------

    @Test
    fun `rebirth multiplies stats by 0_85 with floor 10`() {
        val (g, _) = rebirth(elder(), pipeline)
        // 70*0.85=59.5→59 ; 64*0.85=54.4→54 ; 12*0.85=10.2→10
        assertEquals(59, g.leadership, "leadership 70*0.85→59")
        assertEquals(54, g.strength, "strength 64*0.85→54")
        assertEquals(10, g.intel, "intel 12*0.85=10.2→10")
    }

    @Test
    fun `rebirth floors a low stat at 10`() {
        val low = elder().copy(intel = 5)   // 5*0.85=4.25 < 10 → clamp 10
        val (g, _) = rebirth(low, pipeline)
        assertEquals(10, g.intel, "intel floored at 10")
    }

    @Test
    fun `rebirth halves experience dedication and dex, zeroes injury age specage`() {
        val (g, _) = rebirth(elder(), pipeline)
        assertEquals(500.0, g.experience, "exp *0.5")
        assertEquals(1000.0, g.dedication, "ded *0.5")
        assertEquals(0, g.injury, "injury 0")
        assertEquals(20, metaInt(g.meta, "age"), "age 20")
        assertEquals(0, metaInt(g.meta, "specage"), "specage 0")
        assertEquals(0, metaInt(g.meta, "specage2"), "specage2 0")
        assertEquals(500.0, metaDouble(g.meta, "dex1"), "dex1 1000*0.5")
        assertEquals(1000.0, metaDouble(g.meta, "dex2"), "dex2 2000*0.5")
    }

    @Test
    fun `rebirth emits the PLAIN retire log and the global succession log`() {
        val (_, logs) = rebirth(elder(), pipeline)
        assertTrue(logs.plainLogs.any { it.contains("나이가 들어 <R>은퇴</>하고 자손에게 자리를 물려줍니다.") },
            "PLAIN retire log; got ${logs.plainLogs}")
        assertTrue(logs.globalActionLogs.any { it.contains("<R>은퇴</>하고 그 자손이 유지를 이어받았습니다.") },
            "global succession log; got ${logs.globalActionLogs}")
    }

    // -------- che_은퇴 command --------

    @Test
    fun `che_은퇴 key name and 2-turn cooldown`() {
        val a = CheEuntwe(pipeline)
        assertEquals("che_은퇴", a.key)
        assertEquals("은퇴", a.name)
        assertEquals(1, a.preReqTurn, "getPreReqTurn 1 → 2-turn")
    }

    @Test
    fun `che_은퇴 reqAge 60 constraint`() {
        val names = CheEuntwe(pipeline).buildConstraints(
            ConstraintContext(actorId = 42, nationId = 7, mode = ConstraintMode.FULL)).map { it.name }
        assertTrue("ReqGeneralValue" in names, "reqAge via ReqGeneralValue; got $names")
    }

    @Test
    fun `che_은퇴 runs rebirth and emits retire logs without an inheritance bump`() {
        val draft = GeneralActionDraft(elder(), city(), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheEuntwe(pipeline).resolve(ctx)

        // rebirth applied
        assertEquals(59, draft.general.leadership, "rebirth leadership")
        assertEquals(500.0, draft.general.experience, "rebirth exp")
        assertEquals(20, metaInt(draft.general.meta, "age"), "rebirth age")

        // action logs: rebirth PLAIN then the command MONTH line
        val logs = ctx.logs()
        assertTrue(logs.any { it == "<C>●</>${MONTH}월:은퇴하였습니다. <1>$date</>" }, "command retire log; got $logs")
        val plains = ctx.plainLogs()
        assertTrue(plains.any { it.contains("나이가 들어 <R>은퇴</>하고") }, "rebirth PLAIN log; got $plains")
        // no inheritance bump: meta has no active_action rank delta written by the command
        assertTrue(draft.general.meta["active_action_inherit_delta"] == null, "no inheritance bump")
    }

    @Test
    fun `registry resolves che_은퇴`() {
        assertTrue(CommandRegistry(pipeline).resolve("che_은퇴") is CheEuntwe)
    }
}
