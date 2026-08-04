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
import opensamguk.logic.domain.metaInt
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port-faithful test for the JOIN family — che_임관 + che_장수대상임관.
 *
 * Pins, faithful to `che_임관.php` / `che_장수대상임관.php` run():
 *   - exp 700 when destNation.gennum < GameConst.initialNationGenLimit (10); else 100.
 *   - JOIN transition: nation = destNationID, officer_level = 1, officer_city = 0 (meta), belong = 1 (meta),
 *     che_임관 ALSO troop = 0 (che_장수대상임관 does NOT reset troop), city = destLord's city (or destGeneral's).
 *   - destNation.gennum + 1.
 *   - 3 logs: pushGeneralActionLog (MONTH), pushGeneralHistoryLog (here folded into the action log set
 *     scope — see resolve), pushGlobalActionLog (MONTH global). The action log "<D>{name}</>에 임관했습니다."
 *     for che_임관, "<D>{name}</>에 임관했습니다." for che_장수대상임관 (identical body).
 *   - increaseInheritancePoint(active_action, 1) is the succession-subsystem seam (OQ7/P6) — NO state write here.
 *   - No gameplay RNG (only the trailing unique lottery, which rides a separate rng).
 */
class JoinTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 3
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"

    private fun freshRng(cls: String) = RandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 190, MONTH, 42, cls)))

    // neutral actor (재야) — JOIN precondition BeNeutral
    private fun general() = General(
        id = 42,
        nationId = 0,
        cityId = 0,
        leadership = 70, strength = 70, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0,
        officerLevel = 0,
        gold = 1000, rice = 1000,
        troop = 99,                              // a stale troop membership the JOIN must clear (임관)
        npcType = 0,
        meta = linkedMapOf("explevel" to 10),
    )

    private fun destLordCity() = City(
        id = 500, nationId = 7, level = 5,
        commerce = 1000, commerceMax = 20000, agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private val env = WorldEnv(year = 190, startYear = 184, develCost = 120)
    private val date = "12:34"

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    // -------- che_임관 --------

    @Test
    fun `che_임관 key and name`() {
        val a = CheImgwan(pipeline)
        assertEquals("che_임관", a.key)
        assertEquals("임관", a.name)
        assertEquals("인사", a.category)
    }

    @Test
    fun `che_임관 exp is 700 when destNation gennum below initialNationGenLimit`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val draft = GeneralActionDraft(general(), destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_임관"), env, MONTH, date)
        CheImgwan(pipeline).resolve(ctx)

        assertEquals(700.0, draft.general.experience, "exp +700 (gennum 5 < 10)")
    }

    @Test
    fun `che_임관 exp is 100 when destNation gennum at-or-above initialNationGenLimit`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 10)
        val draft = GeneralActionDraft(general(), destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_임관"), env, MONTH, date)
        CheImgwan(pipeline).resolve(ctx)

        assertEquals(100.0, draft.general.experience, "exp +100 (gennum 10 >= 10)")
    }

    @Test
    fun `che_임관 folds experience before level bookkeeping then keeps the stat-finalize plain-log order`() {
        val foldedPipeline = GeneralActionPipeline(listOf(doubleExperience))
        val actor = general().copy(
            experience = 900.0,
            meta = linkedMapOf("explevel" to 9, "strength_exp" to 30),
        )
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val draft = GeneralActionDraft(actor, destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val context = GeneralActionResolveContext(draft, freshRng("che_임관"), env, MONTH, date)

        CheImgwan(foldedPipeline).resolve(context)

        assertEquals(2300.0, draft.general.experience)
        assertEquals(15, metaInt(draft.general.meta, "explevel"))
        assertEquals(71, draft.general.strength)
        assertEquals(0.0, draft.general.meta["strength_exp"])
        assertEquals(
            listOf(
                "<C>●</><C>Lv 15</>로 <C>레벨업</>!",
                "<C>●</><S>무력</>이 <C>1</> 올랐습니다!",
            ),
            context.plainLogs(),
        )
    }

    @Test
    fun `che_임관 transition fields nation officer_level officer_city belong troop city`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val draft = GeneralActionDraft(general(), destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_임관"), env, MONTH, date)
        CheImgwan(pipeline).resolve(ctx)

        val g = draft.general
        assertEquals(7, g.nationId, "nation = destNationID")
        assertEquals(1, g.officerLevel, "officer_level = 1")
        assertEquals(0, metaInt(g.meta, "officer_city"), "officer_city = 0")
        assertEquals(1, metaInt(g.meta, "belong"), "belong = 1")
        assertEquals(0, g.troop, "troop = 0 (che_임관 resets troop)")
        assertEquals(500, g.cityId, "city = destLord's city")
    }

    @Test
    fun `che_임관 increments destNation gennum`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val draft = GeneralActionDraft(general(), destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_임관"), env, MONTH, date)
        CheImgwan(pipeline).resolve(ctx)

        assertEquals(6, draft.destNation!!.gennum, "destNation gennum + 1")
    }

    @Test
    fun `che_임관 publishes PHP trailing unique lottery intent`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val draft = GeneralActionDraft(general(), destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(
            draft,
            freshRng("che_임관"),
            env,
            MONTH,
            date,
            args = linkedMapOf("destNationID" to 7, "relYear" to 6, "actorNpcType" to 0),
        )
        val command = CheImgwan(pipeline)

        command.resolve(ctx)

        assertEquals(42, command.lastUniqueLotteryIntent?.generalId)
        assertEquals("임관", command.lastUniqueLotteryIntent?.seedReason)
        assertEquals("아이템", command.lastUniqueLotteryIntent?.acquireType)
        assertEquals("setResultTurn>checkStatChange>StaticEventHandler", command.lastUniqueLotteryIntent?.afterTail)
        assertEquals("임관", draft.general.lastTurn.command)
        assertEquals(linkedMapOf("destNationID" to 7), draft.general.lastTurn.arg)
    }

    @Test
    fun `che_임관 emits action history and global logs using context generalName`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val draft = GeneralActionDraft(general().copy(meta = linkedMapOf("explevel" to 10, "name" to "조조")), destLordCity(), null).apply {
            this.destNation = destNation
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_임관"), env, MONTH, date, generalName = "유비")
        CheImgwan(pipeline).resolve(ctx)

        val logs = ctx.logs()
        assertEquals(1, logs.size, "one action log line")
        assertEquals("<C>●</>${MONTH}월:<D>위</>에 임관했습니다. <1>$date</>", logs[0])
        assertEquals(listOf("<C>●</>190년 3월:<D><b>위</b></>에 임관"), ctx.generalHistoryLogs())

        val globals = ctx.globalActionLogs()
        assertEquals(1, globals.size, "one global action log line")
        assertEquals("<C>●</>${MONTH}월:<Y>유비</>가 <D><b>위</b></>에 <S>임관</>했습니다.", globals[0])
    }

    @Test
    fun `che_랜덤임관 sets LastTurn finalizes stats calls StaticEvent and publishes logs`() {
        val observed = mutableListOf<String>()
        StaticEventHandler.register("che_랜덤임관") { general, _, _, _ ->
            observed += "${general.lastTurn.command}:${general.strength}:${general.meta["strength_exp"]}"
        }
        val actor = general().copy(meta = linkedMapOf("explevel" to 10, "name" to "메타명", "strength_exp" to 30))
        val draft = GeneralActionDraft(actor, destLordCity(), null)
        val command = CheRandomImgwan(
            pipeline = pipeline,
            weightedCandidates = listOf(
                RandomImgwanWeightedCandidate(
                    nationId = 7,
                    name = "위",
                    gennum = 5,
                    warpower = 100.0,
                    develpower = 100.0,
                    npcLeq1 = false,
                    lordCityId = 500,
                ),
            ),
        )
        val ctx = GeneralActionResolveContext(draft, freshRng("che_랜덤임관"), env, MONTH, date, generalName = "유비")

        command.resolve(ctx)

        assertEquals(7, draft.general.nationId)
        assertEquals(500, draft.general.cityId)
        assertEquals("무작위 국가로 임관", draft.general.lastTurn.command)
        assertEquals(71, draft.general.strength)
        assertEquals(0.0, draft.general.meta["strength_exp"])
        assertEquals(listOf("무작위 국가로 임관:71:0.0"), observed)
        assertEquals(listOf("<C>●</>190년 3월:<D><b>위</b></>에 랜덤 임관"), ctx.generalHistoryLogs())
        assertTrue(ctx.globalActionLogs().single().contains("<Y>유비</>가 "), "global log uses context.generalName")
        assertEquals("무작위 국가로 임관", command.lastUniqueLotteryIntent?.seedReason)
        assertEquals("랜덤 임관", command.lastUniqueLotteryIntent?.acquireType)
    }

    // -------- che_장수대상임관 --------

    @Test
    fun `che_장수대상임관 key and name`() {
        val a = CheJangsuDaesangImgwan(pipeline)
        assertEquals("che_장수대상임관", a.key)
        assertEquals("장수를 따라 임관", a.name)
    }

    @Test
    fun `che_장수대상임관 does NOT reset troop and joins the dest general's city`() {
        val destNation = Nation(id = 7, level = 3, capitalCityId = 500, name = "위", gennum = 5)
        val destGeneral = General(
            id = 88, nationId = 7, cityId = 333,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 0, rice = 0,
        )
        val draft = GeneralActionDraft(general(), destLordCity(), null).apply {
            this.destNation = destNation
            this.destGeneral = destGeneral
            this.destCity = destLordCity()
        }
        val ctx = GeneralActionResolveContext(draft, freshRng("che_장수대상임관"), env, MONTH, date)
        CheJangsuDaesangImgwan(pipeline).resolve(ctx)

        val g = draft.general
        assertEquals(99, g.troop, "che_장수대상임관 does NOT reset troop")
        assertEquals(333, g.cityId, "city = destGeneral's city")
        assertEquals(7, g.nationId)
        assertEquals(1, g.officerLevel)
        assertEquals(6, draft.destNation!!.gennum, "gennum + 1")
        assertEquals(700.0, g.experience, "exp +700")
        assertEquals(linkedMapOf("destGeneralID" to 88), g.lastTurn.arg)
    }

    // -------- registry --------

    @Test
    fun `registry resolves che_임관 and che_장수대상임관`() {
        val r = CommandRegistry(pipeline)
        assertTrue(r.resolve("che_임관") is CheImgwan)
        assertTrue(r.resolve("che_장수대상임관") is CheJangsuDaesangImgwan)
    }

    @Test
    fun `che_임관 constraints include BeNeutral AllowJoinAction AllowJoinDestNation`() {
        val ctx = ConstraintContext(actorId = 42, nationId = 0, destNationId = 7,
            args = mapOf("relYear" to 6), mode = ConstraintMode.FULL)
        val names = CheImgwan(pipeline).buildConstraints(ctx).map { it.name }.toSet()
        assertTrue("BeNeutral" in names, "BeNeutral; got $names")
        assertTrue("AllowJoinAction" in names, "AllowJoinAction; got $names")
        assertTrue("AllowJoinDestNation" in names, "AllowJoinDestNation; got $names")
    }

    private companion object {
        val doubleExperience = object : GeneralActionModule {
            override fun onCalcStat(
                general: General,
                statName: String,
                value: Double,
                aux: Map<String, Any?>,
            ): Double = if (statName == "experience") value * 2.0 else value
        }
    }
}
