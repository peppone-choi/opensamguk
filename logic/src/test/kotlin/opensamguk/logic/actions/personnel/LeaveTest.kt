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
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful test for the LEAVE family — che_하야 + che_방랑.
 *
 * che_하야 (`che_하야.php` run()):
 *   - betray multiply: experience *= (1 - 0.1*betray); dedication *= (1 - 0.1*betray); betray + 1 cap 9.
 *   - gold/rice clamp: newGold = valueFit(gold, max=defaultGold); the lost delta returns to the nation.
 *   - nation=0, officer_level=0, officer_city=0(meta), belong=0(meta), makelimit=12(meta).
 *   - nation gennum -= (NPCType != 5 ? 1 : 0).
 *   - troop dissolution: if the actor is its own troop leader, all members troop=0 + the troop row drops.
 *
 * che_방랑 (`che_방랑.php` run()) — lord-only bulk CASCADE in load-bearing ORDER:
 *   nation revert (name=generalName,color=#330000,level=0,type=None,tech=0,capital=0)
 *   → general makelimit=12 (whole nation) → self officer_city=0 → general officer_level=1/officer_city=0
 *     (officer_level<12) → city nation=0/front=0/conflict={} → diplomacy state=2/term=0 (me OR you).
 *   No lottery.
 */
class LeaveTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 5
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private fun freshRng(cls: String) = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 200, MONTH, 42, cls)))
    private val env = WorldEnv(year = 200, startYear = 184, develCost = 120)
    private val date = "09:30"

    // -------- che_하야 --------

    private fun retiree(npcType: Int = 0, betray: Int = 2, gold: Int = 5000, rice: Int = 4000) = General(
        id = 42, nationId = 7, cityId = 500,
        leadership = 70, strength = 70, intel = 80, injury = 0,
        experience = 1000.0, dedication = 2000.0,
        officerLevel = 3, gold = gold, rice = rice,
        troop = 42,                                   // own troop leader
        npcType = npcType,
        meta = linkedMapOf("explevel" to 10, "dedlevel" to 5, "betray" to betray, "belong" to 1),
    )

    private fun homeCity() = City(
        id = 500, nationId = 7, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun homeNation(gold: Int = 100000, rice: Int = 100000, gennum: Int = 8) =
        Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gold = gold, rice = rice, gennum = gennum)

    @Test
    fun `che_하야 key name category`() {
        val a = CheHaya(pipeline)
        assertEquals("che_하야", a.key)
        assertEquals("하야", a.name)
    }

    @Test
    fun `che_하야 applies betray multiply to exp and dedication and bumps betray with cap 9`() {
        val draft = GeneralActionDraft(retiree(betray = 2), homeCity(), homeNation())
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)

        // (1 - 0.1*2) = 0.8 ; 1000*0.8 = 800 ; 2000*0.8 = 1600
        assertEquals(800.0, draft.general.experience, "exp *= 0.8")
        assertEquals(1600.0, draft.general.dedication, "ded *= 0.8")
        assertEquals(3, metaInt(draft.general.meta, "betray"), "betray + 1")
    }

    @Test
    fun `che_하야 betray caps at 9`() {
        val draft = GeneralActionDraft(retiree(betray = 9), homeCity(), homeNation())
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)
        assertEquals(9, metaInt(draft.general.meta, "betray"), "betray capped at 9")
    }

    @Test
    fun `che_하야 clamps gold rice to defaults and returns the lost delta to the nation`() {
        val draft = GeneralActionDraft(retiree(gold = 5000, rice = 4000), homeCity(), homeNation(gold = 100000, rice = 100000))
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)

        // defaultGold/defaultRice = 1000 ; lost = 5000-1000=4000 gold, 4000-1000=3000 rice
        assertEquals(1000, draft.general.gold, "gold clamped to defaultGold")
        assertEquals(1000, draft.general.rice, "rice clamped to defaultRice")
        assertEquals(100000 + 4000, draft.nation!!.gold, "nation gold += lostGold")
        assertEquals(100000 + 3000, draft.nation!!.rice, "nation rice += lostRice")
    }

    @Test
    fun `che_하야 resets membership fields`() {
        val draft = GeneralActionDraft(retiree(), homeCity(), homeNation())
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)

        val g = draft.general
        assertEquals(0, g.nationId, "nation 0")
        assertEquals(0, g.officerLevel, "officer_level 0")
        assertEquals(0, metaInt(g.meta, "officer_city"), "officer_city 0")
        assertEquals(0, metaInt(g.meta, "belong"), "belong 0")
        assertEquals(12, metaInt(g.meta, "makelimit"), "makelimit 12")
        assertEquals(0, g.troop, "troop 0")
    }

    @Test
    fun `che_하야 decrements nation gennum when NPCType is not 5`() {
        val draft = GeneralActionDraft(retiree(npcType = 0), homeCity(), homeNation(gennum = 8))
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)
        assertEquals(7, draft.nation!!.gennum, "gennum - 1 (npc != 5)")
    }

    @Test
    fun `che_하야 keeps nation gennum when NPCType is 5`() {
        val draft = GeneralActionDraft(retiree(npcType = 5), homeCity(), homeNation(gennum = 8))
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)
        assertEquals(8, draft.nation!!.gennum, "gennum unchanged (npc == 5)")
    }

    @Test
    fun `che_하야 dissolves the troop when the actor is its own leader`() {
        val member = General(
            id = 77, nationId = 7, cityId = 500,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 0, rice = 0,
            troop = 42,
        )
        val draft = GeneralActionDraft(retiree(), homeCity(), homeNation()).apply {
            cascadeGenerals.add(member)
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_하야"), env, MONTH, date)
        CheHaya(pipeline).resolve(ctx)

        assertTrue(draft.cascadeGenerals.all { it.troop == 0 }, "all troop members reset to 0")
    }

    // -------- che_방랑 --------

    @Test
    fun `che_방랑 key name`() {
        val a = CheBangrang(pipeline)
        assertEquals("che_방랑", a.key)
        assertEquals("방랑", a.name)
    }

    @Test
    fun `che_방랑 cascade reverts nation generals cities diplomacy in order`() {
        val lord = General(
            id = 42, nationId = 7, cityId = 500,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 0, rice = 0,
            meta = linkedMapOf("name" to "원소"),
        )
        val member = General(
            id = 77, nationId = 7, cityId = 501,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 5, gold = 0, rice = 0,
            meta = linkedMapOf("officer_city" to 501),
        )
        val nation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", color = "#abcdef",
            typeCode = "che_병가", tech = 1234.0)
        val city1 = City(id = 500, nationId = 7, level = 5, commerce = 1, commerceMax = 1,
            agriculture = 1, agricultureMax = 1, supplyState = 1, frontState = 1, trust = 50.0,
            meta = linkedMapOf("conflict" to linkedMapOf("3" to 5)))
        val city2 = city1.copy(id = 501, frontState = 3)
        val diplo = Diplomacy(me = 7, you = 9, state = 0, term = 4)

        val draft = GeneralActionDraft(lord, city1, nation).apply {
            cascadeGenerals.add(member)
            cascadeCities.add(city1)
            cascadeCities.add(city2)
            cascadeDiplomacy.add(diplo)
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_방랑"), env, MONTH, date)
        CheBangrang(pipeline).resolve(ctx)

        // nation revert
        val n = draft.nation!!
        assertEquals("원소", n.name, "nation name = lord name")
        assertEquals("#330000", n.color, "wandering color")
        assertEquals(0, n.level, "level 0")
        assertEquals("None", n.typeCode, "type None")
        assertEquals(0.0, n.tech, "tech 0")
        assertEquals(0, n.capitalCityId, "capital 0")

        // lord: makelimit 12, officer_city 0 (lord officer_level==12 so officer_level untouched)
        assertEquals(12, metaInt(draft.general.meta, "makelimit"), "lord makelimit 12")
        assertEquals(0, metaInt(draft.general.meta, "officer_city"), "lord officer_city 0")
        assertEquals(12, draft.general.officerLevel, "lord officer_level untouched (==12)")

        // member: makelimit 12, officer_level reset to 1 (was 5 < 12), officer_city 0
        val m = draft.cascadeGenerals[0]
        assertEquals(12, metaInt(m.meta, "makelimit"), "member makelimit 12")
        assertEquals(1, m.officerLevel, "member officer_level 1")
        assertEquals(0, metaInt(m.meta, "officer_city"), "member officer_city 0")

        // cities: nation 0, front 0, conflict {}
        assertTrue(draft.cascadeCities.all { it.nationId == 0 }, "cities nation 0")
        assertTrue(draft.cascadeCities.all { it.frontState == 0 }, "cities front 0")
        assertTrue(draft.cascadeCities.all {
            @Suppress("UNCHECKED_CAST")
            ((it.meta["conflict"] as? Map<String, Any?>) ?: emptyMap()).isEmpty()
        }, "cities conflict {}")

        // diplomacy: state 2, term 0
        assertTrue(draft.cascadeDiplomacy.all { it.state == 2 && it.term == 0 }, "diplomacy state 2 term 0")
    }

    @Test
    fun `che_방랑 has no lottery and emits an action log`() {
        val lord = General(
            id = 42, nationId = 7, cityId = 500,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 0, rice = 0,
            meta = linkedMapOf("name" to "원소"),
        )
        val nation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위")
        val draft = GeneralActionDraft(lord, homeCity().copy(nationId = 7), nation)
        val ctx = GeneralActionResolveContext(draft, freshRng("che_방랑"), env, MONTH, date)
        CheBangrang(pipeline).resolve(ctx)

        val logs = ctx.logs()
        assertEquals(1, logs.size, "one action log")
        assertEquals("<C>●</>${MONTH}월:영토를 버리고 방랑의 길을 떠납니다. <1>$date</>", logs[0])
    }

    @Test
    fun `registry resolves che_하야 and che_방랑`() {
        val r = CommandRegistry(pipeline)
        assertTrue(r.resolve("che_하야") is CheHaya)
        assertTrue(r.resolve("che_방랑") is CheBangrang)
    }

    @Test
    fun `che_방랑 constraints include BeLord NotWanderingNation NotOpeningPart AllowDiplomacyStatus`() {
        val ctx = ConstraintContext(actorId = 42, nationId = 7,
            args = mapOf("relYear" to 16), mode = ConstraintMode.FULL)
        val names = CheBangrang(pipeline).buildConstraints(ctx).map { it.name }.toSet()
        assertTrue("BeLord" in names)
        assertTrue("NotWanderingNation" in names)
        assertTrue("NotOpeningPart" in names)
        assertTrue("AllowDiplomacyStatus" in names)
    }
}
