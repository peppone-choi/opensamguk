package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.MustNotBeReachedException
import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
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
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GOLDEN — che_등용수락 ([CheDeungyongSurak]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/General/che_등용수락.php` `run(RandUtil $rng)`는 RNG를 단 한 번도
 * 끌지 않는다(draw COUNT = 0; 말미 unique 로터리 미발생 — 망명 액션). docker 골든 불필요 — 본 테스트가 골든을
 * 대신한다:
 *
 *  - **0-draw**: [NoRng]-backed [RandUtil]로 resolve. 한 번이라도 draw하면 [MustNotBeReachedException].
 *  - **국가 이적 effect**: nation/officer_level/officer_city/belong/permission/troop/city 전이 + 양 국가 gennum
 *    + 옛 국가 gold/rice 환수 + betray 감쇠/증가 (두 분기: 재야 / 비-재야).
 *  - **로그 byte-exact**: actor action(L170), dest action(L171), global action(L176) 3건 + 순서.
 *    history 2건은 sink 부재로 [CheDeungyongSurak] 주석에 byte-exact 보존(WAVE-4 이연, 날조/약화 없음).
 *
 * 이름/josa: actor='조운', destNation='오'. '오'+'로'→'로'(받침 없음), '조운'+'이'→'이'(받침 ㄴ).
 */
class CheDeungyongSurakGoldenTest {

    private fun pipeline() = GeneralActionPipeline()

    private val env = WorldEnv(year = 200, startYear = 190, develCost = 100)
    private val date = "12:34"
    private val MONTH = 3

    @AfterTest
    fun clearStaticEventHandlers() = StaticEventHandler.clear()

    /** 망명 대상국 '오' (id=5, capital=30, gennum=4). */
    private fun destNation() = Nation(id = 5, level = 3, capitalCityId = 30, name = "오", gennum = 4)

    private fun city() = City(
        id = 1, nationId = 1, level = 5,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun context(
        general: General,
        nation: Nation?,
        rng: RandUtil = RandUtil(NoRng()),
        destGeneral: General? = General(
            id = 99, nationId = 5, cityId = 30,
            leadership = 60, strength = 60, intel = 60, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 5, gold = 0, rice = 0,
            meta = linkedMapOf("explevel" to 1, "dedlevel" to 1),
        ),
        args: Map<String, Any?> = mapOf("destGeneralID" to 99, "destNationID" to 5),
    ): GeneralActionResolveContext {
        val draft = GeneralActionDraft(general = general, city = city(), nation = nation).apply {
            this.destNation = destNation()
            this.destGeneral = destGeneral
        }
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = MONTH, date = date, args = args,
        )
    }

    /** 비-재야 actor '조운' (nation=1, betray=2, gold=3000, rice=2500). */
    private fun memberActor() = General(
        id = 7, nationId = 1, cityId = 1,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 500.0, dedication = 500.0, officerLevel = 3, gold = 3000, rice = 2500,
        troop = 0, npcType = 0,
        meta = linkedMapOf("name" to "조운", "betray" to 2, "explevel" to 5, "dedlevel" to 3, "belong" to 7),
    )

    /** 재야 actor '조운' (nation=0). */
    private fun neutralActor() = General(
        id = 7, nationId = 0, cityId = 1,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0, gold = 500, rice = 500,
        troop = 0, npcType = 0,
        meta = linkedMapOf("name" to "조운", "explevel" to 1, "dedlevel" to 1),
    )

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws (member branch)`() {
        CheDeungyongSurak(pipeline()).resolve(context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6)))
    }

    @Test
    fun `resolve makes zero rng draws (neutral branch)`() {
        CheDeungyongSurak(pipeline()).resolve(context(neutralActor(), null))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(neutralActor(), null)
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── 이적 전이 (transition) ────────────────────────────────────────────────────────────────────
    @Test
    fun `member transition fields nation officerLevel officerCity belong permission troop city`() {
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val g = ctx.draft.general
        assertEquals(5, g.nationId, "nation = destNationID")
        assertEquals(1, g.officerLevel, "officer_level = 1")
        assertEquals(0, metaInt(g.meta, "officer_city"), "officer_city = 0")
        assertEquals(1, metaInt(g.meta, "belong"), "belong = 1")
        assertEquals("normal", g.meta["permission"], "permission = normal")
        assertEquals(0, g.troop, "troop = 0")
        assertEquals(30, g.cityId, "city = destNation.capital")
    }

    @Test
    fun `both nation gennum updated and old nation receives gold rice surrender`() {
        val oldNation = Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gold = 100, rice = 200, gennum = 6)
        val ctx = context(memberActor(), oldNation)
        CheDeungyongSurak(pipeline()).resolve(ctx)
        // 옛 국가 gennum -1, 대상국 gennum +1.
        assertEquals(5, ctx.draft.nation!!.gennum, "old nation gennum 6 -> 5")
        assertEquals(5, ctx.draft.destNation!!.gennum, "dest nation gennum 4 -> 5")
        // gold 3000 -> 1000 (defaultGold), 환수 2000 → 옛 국가 gold 100 + 2000 = 2100.
        assertEquals(GameConst.defaultGold, ctx.draft.general.gold, "actor gold clamped to defaultGold")
        assertEquals(2100, ctx.draft.nation!!.gold, "old nation gold += lost (100 + 2000)")
        // rice 2500 -> 1000, 환수 1500 → 옛 국가 rice 200 + 1500 = 1700.
        assertEquals(GameConst.defaultRice, ctx.draft.general.rice, "actor rice clamped to defaultRice")
        assertEquals(1700, ctx.draft.nation!!.rice, "old nation rice += lost (200 + 1500)")
    }

    @Test
    fun `member betray decays exp ded and increments betray`() {
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val g = ctx.draft.general
        // betray=2 → factor 0.8 → exp 500*0.8=400, ded 500*0.8=400.
        assertEquals(400.0, g.experience, "experience *= (1 - 0.1*betray)")
        assertEquals(400.0, g.dedication, "dedication *= (1 - 0.1*betray)")
        // betray 2 -> 3 (cap 9).
        assertEquals(3, metaInt(g.meta, "betray"), "betray + 1")
    }

    @Test
    fun `neutral branch awards exp ded 100 and does not touch betray`() {
        val ctx = context(neutralActor(), null)
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val g = ctx.draft.general
        // 재야: 본인 +exp100/+ded100. gold/rice 미환수(재야 분기 — surrender 없음).
        assertEquals(100.0, g.experience, "neutral actor exp +100")
        assertEquals(100.0, g.dedication, "neutral actor ded +100")
        assertEquals(500, g.gold, "neutral actor gold untouched")
        assertEquals(500, g.rice, "neutral actor rice untouched")
        // 이적 전이는 동일.
        assertEquals(5, g.nationId)
        assertEquals(1, g.officerLevel)
        assertEquals(30, g.cityId)
        // dest nation gennum +1; 옛 국가 없음(nation=null) → 미변경.
        assertEquals(5, ctx.draft.destNation!!.gennum)
        assertEquals(null, ctx.draft.nation)
    }

    @Test
    fun `dest general receives exp ded 100`() {
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val dg = ctx.draft.destGeneral!!
        assertEquals(100.0, dg.experience, "dest general exp +100")
        assertEquals(100.0, dg.dedication, "dest general ded +100")
    }

    @Test
    fun `npcType below 2 sets killturn from env arg`() {
        val ctx = context(
            neutralActor(), null,
            args = mapOf("destGeneralID" to 99, "destNationID" to 5, "__killturn" to 720),
        )
        CheDeungyongSurak(pipeline()).resolve(ctx)
        assertEquals(720, metaInt(ctx.draft.general.meta, "killturn"), "npc<2 → killturn = env killturn")
    }

    @Test
    fun `npcType at or above 2 does not set killturn`() {
        val npcActor = neutralActor().copy(npcType = 2)
        val ctx = context(
            npcActor, null,
            args = mapOf("destGeneralID" to 99, "destNationID" to 5, "__killturn" to 720),
        )
        CheDeungyongSurak(pipeline()).resolve(ctx)
        assertEquals(0, metaInt(ctx.draft.general.meta, "killturn"), "npc>=2 → killturn untouched")
    }

    @Test
    fun `troop leader dissolves members`() {
        // actor가 자기 부대 리더(troop == id). 부대원(troop=7)은 troop=0 으로.
        val leader = memberActor().copy(troop = 7)
        val ctx = context(leader, Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        val member = General(
            id = 8, nationId = 1, cityId = 1,
            leadership = 50, strength = 50, intel = 50, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 0, rice = 0, troop = 7,
        )
        ctx.draft.cascadeGenerals.add(member)
        CheDeungyongSurak(pipeline()).resolve(ctx)
        assertEquals(0, ctx.draft.cascadeGenerals[0].troop, "member troop -> 0")
        assertEquals(0, ctx.draft.general.troop, "leader troop -> 0")
    }

    // ── 로그 byte-exact ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `actor action log is byte-exact`() {
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size, "one actor action log")
        // L170 generalAction (MONTH '<C>●</>{month}월:'). '오'+'로'→'로'.
        assertEquals("<C>●</>3월:<D>오</>로 망명하여 수도로 이동합니다.", logs[0])
    }

    @Test
    fun `dest action log is byte-exact`() {
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        CheDeungyongSurak(pipeline()).resolve(ctx)
        // L171 dest generalAction (MONTH) — dest 장수 id=99 스코프.
        val destLogs = ctx.logsTo(99)
        assertEquals(1, destLogs.size, "one dest action log")
        assertEquals("<C>●</>3월:<Y>조운</> 등용에 성공했습니다.", destLogs[0])
    }

    @Test
    fun `global action log is byte-exact`() {
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))
        CheDeungyongSurak(pipeline()).resolve(ctx)
        val globals = ctx.globalActionLogs()
        assertEquals(1, globals.size, "one global action log")
        // L176 globalAction (MONTH). '조운'+'이'→'이', '오'+'로'→'로'.
        assertEquals(
            "<C>●</>3월:<Y>조운</>이 <D><b>오</b></>로 <S>망명</>하였습니다.",
            globals[0],
        )
    }

    @Test
    fun `tail dispatches static event after the final nation transfer`() {
        val observed = mutableListOf<String>()
        StaticEventHandler.register("che_등용수락") { general, destGeneral, _, params ->
            observed += "${general.nationId}:${general.officerLevel}:${general.cityId}:${general.troop}:${destGeneral?.experience}:${params["destNationID"]}"
        }
        val ctx = context(memberActor(), Nation(id = 1, level = 3, capitalCityId = 1, name = "촉", gennum = 6))

        CheDeungyongSurak(pipeline()).resolve(ctx)

        assertEquals(listOf("5:1:30:0:100.0:5"), observed)
    }

    // ── registry / metadata ─────────────────────────────────────────────────────────────────────
    @Test
    fun `registry resolves che_등용수락`() {
        val r = CommandRegistry(pipeline())
        assertTrue(r.resolve("che_등용수락") is CheDeungyongSurak)
    }

    @Test
    fun `command is non-reservable`() {
        assertEquals(false, CheDeungyongSurak(pipeline()).reservable, "che_등용수락 is NOT reservable (AlwaysFail)")
    }

    @Test
    fun `key name category`() {
        val a = CheDeungyongSurak(pipeline())
        assertEquals("che_등용수락", a.key)
        assertEquals("등용 수락", a.name)
        assertEquals("인사", a.category)
    }

    @Test
    fun `parseArgs keeps destGeneralID and destNationID`() {
        val a = CheDeungyongSurak(pipeline())
        val parsed = a.parseArgs(mapOf("destGeneralID" to 99, "destNationID" to 5))
        assertEquals(99, parsed["destGeneralID"])
        assertEquals(5, parsed["destNationID"])
    }

    @Test
    fun `parseArgs rejects non-positive ids`() {
        val a = CheDeungyongSurak(pipeline())
        assertTrue(a.parseArgs(mapOf("destGeneralID" to 0, "destNationID" to 5)).isEmpty())
        assertTrue(a.parseArgs(mapOf("destGeneralID" to 99, "destNationID" to 0)).isEmpty())
        assertTrue(a.parseArgs(mapOf("destNationID" to 5)).isEmpty())
    }

    @Test
    fun `constraints include the full condition pack`() {
        val cctx = ConstraintContext(
            actorId = 7, nationId = 0, cityId = 1,
            env = emptyMap(), args = mapOf("destNationID" to 5, "relYear" to 10),
            destNationId = 5, mode = ConstraintMode.FULL,
        )
        val names = CheDeungyongSurak(pipeline()).buildConstraints(cctx).map { it.name }.toSet()
        assertTrue("ReqEnvValue" in names, "join_mode != onlyRandom; got $names")
        assertTrue("ExistsDestNation" in names)
        assertTrue("BeNeutral" in names)
        assertTrue("AllowJoinDestNation" in names)
        assertTrue("ReqDestNationValue" in names, "level > 0 (방랑군 불가); got $names")
        assertTrue("DifferentDestNation" in names)
        assertTrue("ReqGeneralValue" in names, "officer_level != 12 (군주 불가); got $names")
    }
}
