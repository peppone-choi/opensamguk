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
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port-faithful test for che_등용 SEND ONLY (`che_등용.php`; 등용수락/mailbox DEFERRED to P6).
 *
 *   - getCost(): Util::round(develcost + (dest.exp + dest.ded)/1000) * 10 — round BEFORE the *10
 *     (che_등용.php:111-114). The fixture picks (exp+ded) where the rounding order changes the result.
 *   - run(): send log "<Y>{destName}</>에게 등용 권유 서신을 보냈습니다. <1>{date}</>"; then self-mutation
 *     addExperience(100)/addDedication(200)/increaseVar('leadership_exp',1)/increaseVarWithLimit('gold',-reqGold,0);
 *     a MINIMAL message-row INSERT (letter payload only); NO transition; NO increaseInheritancePoint.
 */
class ScoutTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 7
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private fun freshRng() = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 200, MONTH, 42, "che_등용")))
    // develCost = 100 so the round-before-*10 boundary is exercised.
    private val env = WorldEnv(year = 200, startYear = 184, develCost = 100)
    private val date = "13:45"

    private fun actor(gold: Int = 5000, leadershipExp: Double = 0.0) = General(
        id = 42, nationId = 1, cityId = 7,
        leadership = 70, strength = 70, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 5, gold = gold, rice = 1000,
        meta = linkedMapOf("explevel" to 10, "dedlevel" to 5, "leadership_exp" to leadershipExp, "name" to "조조"),
    )

    // dest general: exp + ded = 1500 → (exp+ded)/1000 = 1.5 ; 100 + 1.5 = 101.5
    private fun destGeneral(officerLevel: Int = 3) = General(
        id = 88, nationId = 2, cityId = 200,
        leadership = 60, strength = 60, intel = 60, injury = 0,
        experience = 900.0, dedication = 600.0, officerLevel = officerLevel, gold = 0, rice = 0,
        meta = linkedMapOf("name" to "관우"),
    )

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1, commerceMax = 1, agriculture = 1, agricultureMax = 1,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private val nation = Nation(id = 1, level = 3, capitalCityId = 7, name = "위")

    @Test
    fun `key name category`() {
        val a = CheDeungyong(pipeline)
        assertEquals("che_등용", a.key)
        assertEquals("등용", a.name)
        assertEquals("인사", a.category)
    }

    @Test
    fun `getCost rounds BEFORE the times-10`() {
        // round(100 + 1500/1000) * 10 = round(101.5) * 10 = 102 * 10 = 1020
        // (rounding AFTER would be round(1015) = 1015 — distinct)
        val cost = CheDeungyong(pipeline).getCost(destGeneral(), env)
        assertEquals(1020, cost, "round-before-*10 → 1020 (not 1015)")
    }

    @Test
    fun `send log byte-exact`() {
        val draft = GeneralActionDraft(actor(), city(), nation).apply { destGeneral = this@ScoutTest.destGeneral() }
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheDeungyong(pipeline).resolve(ctx)

        val logs = ctx.logs()
        assertEquals(1, logs.size, "one action log")
        assertEquals("<C>●</>${MONTH}월:<Y>관우</>에게 등용 권유 서신을 보냈습니다. <1>$date</>", logs[0])
    }

    @Test
    fun `self mutation rewards exp100 ded200 leadership_exp1 and floors gold at 0`() {
        val draft = GeneralActionDraft(actor(gold = 5000, leadershipExp = 3.0), city(), nation).apply {
            destGeneral = this@ScoutTest.destGeneral()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheDeungyong(pipeline).resolve(ctx)

        val g = draft.general
        assertEquals(100.0, g.experience, "+exp 100")
        assertEquals(200.0, g.dedication, "+ded 200")
        assertEquals(4.0, metaDouble(g.meta, "leadership_exp"), "+leadership_exp 1")
        // reqGold = 1020 ; 5000 - 1020 = 3980
        assertEquals(3980, g.gold, "gold -= reqGold (floor 0)")
    }

    @Test
    fun `gold floors at 0 when reqGold exceeds gold`() {
        val draft = GeneralActionDraft(actor(gold = 500), city(), nation).apply {
            destGeneral = this@ScoutTest.destGeneral()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng(), env, MONTH, date)
        CheDeungyong(pipeline).resolve(ctx)
        assertEquals(0, draft.general.gold, "gold floored at 0 (500 - 1020 → 0)")
    }

    @Test
    fun `minimal message row INSERT shape`() {
        val cmd = CheDeungyong(pipeline)
        val draft = GeneralActionDraft(actor(), city(), nation).apply { destGeneral = this@ScoutTest.destGeneral() }
        cmd.resolve(GeneralActionResolveContext(draft, freshRng(), env, MONTH, date))

        val msg = cmd.lastScoutLetter
        assertNotNull(msg, "a scout letter row was produced")
        assertEquals(42, msg.srcGeneralId, "src = actor")
        assertEquals(88, msg.destGeneralId, "dest = destGeneral")
        assertEquals("scout", msg.type, "type scout")
        assertTrue(msg.text.contains("망명 권유 서신"), "letter body; got ${msg.text}")
    }

    @Test
    fun `does not transition the dest general`() {
        val draft = GeneralActionDraft(actor(), city(), nation).apply { destGeneral = this@ScoutTest.destGeneral() }
        CheDeungyong(pipeline).resolve(GeneralActionResolveContext(draft, freshRng(), env, MONTH, date))
        assertEquals(2, draft.destGeneral!!.nationId, "dest general unchanged (no transition)")
        assertEquals(3, draft.destGeneral!!.officerLevel, "dest general officer_level unchanged")
    }

    @Test
    fun `constraints include the SEND pack`() {
        val ctx = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, destGeneralId = 88, mode = ConstraintMode.FULL)
        val names = CheDeungyong(pipeline).buildConstraints(ctx).map { it.name }.toSet()
        assertTrue("NotBeNeutral" in names)
        assertTrue("OccupiedCity" in names)
        assertTrue("SuppliedCity" in names)
        assertTrue("ExistsDestGeneral" in names)
        assertTrue("DifferentNationDestGeneral" in names)
        assertTrue("ReqGeneralGold" in names)
        assertTrue("ReqGeneralRice" in names)
    }

    @Test
    fun `registry resolves che_등용`() {
        assertTrue(CommandRegistry(pipeline).resolve("che_등용") is CheDeungyong)
    }
}
