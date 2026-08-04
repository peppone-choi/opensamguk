package opensamguk.logic.actions.nation

import opensamguk.common.rng.MustNotBeReachedException
import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
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
 * GOLDEN — che_모반시도 ([cheMobanSido]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/General/che_모반시도.php` `run(RandUtil $rng)`는 RNG를 단 한 번도
 * 끌지 않는다(draw COUNT = 0). docker 골든 불필요 — 본 테스트가 run() body를 골든으로 대신한다:
 *
 *  - **0-draw**: [NoRng]-backed [RandUtil]로 resolve. 한 번이라도 draw하면 [MustNotBeReachedException].
 *  - **state delta**: actor officer_level 12 / officer_city 0; lord officer_level 1 / officer_city 0 /
 *    experience *= 0.7 (raw multiplyVar, 레벨 재계산 없음).
 *  - **log byte-exact**: 현 [GeneralActionResolveContext] sink이 버퍼하는 2건(actor generalAction MONTH :92,
 *    lord generalAction MONTH :93)을 byte-exact + 순서로 검증. 나머지 4건(history 스코프)은 sink 부재로
 *    [cheMobanSido] 주석에 byte-exact 보존(WAVE 후속 seam 이연, 날조/약화 없음).
 *
 * 이름: actor='동탁'(탁→받침 ㄱ → '이'), lord='원소', 국명='후한'.
 * josa: '동탁'+'이'→'이'(받침 있음).
 */
class CheMobanSidoGoldenTest {

    private val pipeline = GeneralActionPipeline()

    private val actorName = "동탁"
    private val lordName = "원소"
    private val nationName = "후한"

    @AfterTest
    fun clearStaticEventHandlers() = StaticEventHandler.clear()

    private fun context(
        rng: RandUtil,
        withLord: Boolean = true,
        actorMeta: Map<String, Any?> = emptyMap(),
    ): GeneralActionResolveContext {
        // actor — 수뇌(chief, officer_level 5), 자국(nation 1), city 1.
        val general = General(
            id = 7, nationId = 1, cityId = 1,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 5, gold = 1000, rice = 1000,
            officerCity = 1,
            meta = actorMeta,
        )
        val city = City(
            id = 1, nationId = 1, level = 5,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 50.0,
        )
        val nation = Nation(id = 1, level = 5, capitalCityId = 1, name = nationName, color = "#ff0000")
        val draft = GeneralActionDraft(general = general, city = city, nation = nation)
        if (withLord) {
            // 군주(officer_level=12) — 어댑터가 SELECT … officer_level=12로 적재해 destGeneral로 주입.
            draft.destGeneral = General(
                id = 42, nationId = 1, cityId = 1,
                leadership = 90, strength = 90, intel = 90, injury = 0,
                experience = 1000.0, dedication = 500.0, officerLevel = 12, gold = 5000, rice = 5000,
                officerCity = 0,
            )
        }
        val env = WorldEnv(year = 200, startYear = 190, develCost = 100)
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = 3, date = "12:34",
            args = emptyMap(), generalName = actorName, destGeneralName = lordName,
        )
    }

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws`() {
        cheMobanSido(pipeline).resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── state delta (che_모반시도.php:81-85) ────────────────────────────────────────────────────────
    @Test
    fun `actor becomes lord, lord demoted, lord experience times 0_7`() {
        val ctx = context(RandUtil(NoRng()))
        cheMobanSido(pipeline).resolve(ctx)

        // actor → officer_level 12, officer_city 0 (:81-82)
        assertEquals(12, ctx.draft.general.officerLevel, "[모반] actor officer_level = 12")
        assertEquals(0, ctx.draft.general.officerCity, "[모반] actor officer_city = 0")

        // lord → officer_level 1, officer_city 0, experience *= 0.7 (:83-85)
        val lord = ctx.draft.destGeneral!!
        assertEquals(1, lord.officerLevel, "[모반] lord officer_level = 1")
        assertEquals(0, lord.officerCity, "[모반] lord officer_city = 0")
        assertEquals(1000.0 * 0.7, lord.experience, "[모반] lord experience *= 0.7")
    }

    @Test
    fun `no-op when adapter did not inject the lord general`() {
        val ctx = context(RandUtil(NoRng()), withLord = false)
        cheMobanSido(pipeline).resolve(ctx)
        // destGeneral 부재 → early return; actor 무변(officer_level 5 유지), 로그 없음.
        assertEquals(5, ctx.draft.general.officerLevel)
        assertTrue(ctx.logs().isEmpty())
    }

    // ── log byte-exact (버퍼 가능한 2건 + 순서) ──────────────────────────────────────────────────────
    @Test
    fun `actor action log is byte-exact (MONTH format)`() {
        val ctx = context(RandUtil(NoRng()))
        cheMobanSido(pipeline).resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // (:92) pushGeneralActionLog 기본 MONTH 포맷 → '<C>●</>{month}월:' + body.
        assertEquals("<C>●</>3월:모반에 성공했습니다. <1>12:34</>", logs[0])
    }

    @Test
    fun `lord action log is byte-exact in dest scope`() {
        val ctx = context(RandUtil(NoRng()))
        cheMobanSido(pipeline).resolve(ctx)
        val lordLogs = ctx.logsTo(42)
        assertEquals(1, lordLogs.size)
        assertEquals("<C>●</>3월:<Y>동탁</>에게 군주의 자리를 뺏겼습니다.", lordLogs[0])
    }

    @Test
    fun `actor does not buffer a global-action line (history scope has no sink)`() {
        val ctx = context(RandUtil(NoRng()))
        cheMobanSido(pipeline).resolve(ctx)
        // 4종 history 로그(global/national/general-history)는 sink 부재 → 어떤 버퍼에도 적재되지 않는다.
        assertTrue(ctx.globalActionLogs().isEmpty(), "[모반] history 스코프는 globalAction 버퍼로 새지 않음")
        assertTrue(ctx.plainLogs().isEmpty())
        assertTrue(ctx.plainLogsTo(42).isEmpty())
    }

    @Test
    fun `actor tail writes result turn finalizes stat and then dispatches static event`() {
        val observed = mutableListOf<String>()
        StaticEventHandler.register("che_모반시도") { general, destGeneral, _, params ->
            observed += "${general.lastTurn.command}:${general.leadership}:${metaInt(general.meta, "leadership_exp")}:${destGeneral?.id}:${params.isEmpty()}"
        }
        val ctx = context(
            RandUtil(NoRng()),
            actorMeta = linkedMapOf("leadership_exp" to 30),
        )

        cheMobanSido(pipeline).resolve(ctx)

        assertEquals(listOf("모반시도:81:0:42:true"), observed)
        assertEquals(listOf("<C>●</><S>통솔</>이 <C>1</> 올랐습니다!"), ctx.plainLogs())
    }

    // ── constraints (che_모반시도.php:35-42, PHP ORDER) ──────────────────────────────────────────────
    @Test
    fun `constraints match PHP order and names`() {
        val cmd = cheMobanSido(pipeline)
        val cctx = ConstraintContext(
            actorId = 7, cityId = 1, nationId = 1,
            env = emptyMap(), args = emptyMap(), mode = ConstraintMode.FULL,
        )
        val names = cmd.buildConstraints(cctx).map { it.name }
        assertEquals(
            listOf("NotBeNeutral", "BeChief", "OccupiedCity", "SuppliedCity", "NotLord", "AllowRebellion"),
            names,
            "[모반] constraint set + PHP order",
        )
    }

    @Test
    fun `getPreReqTurn is zero and command is no-arg`() {
        val cmd = cheMobanSido(pipeline)
        assertEquals(0, cmd.getPreReqTurn())
        assertTrue(cmd.parseArgs(mapOf("anything" to 1)).isEmpty())
        assertEquals("che_모반시도", cmd.key)
        assertEquals("모반시도", cmd.name)
    }

    // ── AllowRebellion 로컬 제약 (AllowRebellion.php 전사) ───────────────────────────────────────────
    private fun cctxWithLordStaging(lordStaging: Map<String, Any?>) = ConstraintContext(
        actorId = 7, cityId = 1, nationId = 1,
        env = lordStaging, args = emptyMap(), mode = ConstraintMode.FULL,
    )

    @Test
    fun `allowRebellion allows when staging absent (static precondition)`() {
        val c = allowRebellion()
        // staging(__lordNo) 미주입 → 통과(정적 선결조건; 골든은 제약 미평가).
        val r = c.test(cctxWithLordStaging(emptyMap()), STATE_VIEW_STUB)
        assertEquals(ConstraintResult.Allow, r)
    }

    @Test
    fun `allowRebellion denies when lord is self`() {
        val c = allowRebellion()
        val r = c.test(cctxWithLordStaging(mapOf("__lordNo" to 7)), STATE_VIEW_STUB)
        assertEquals(ConstraintResult.Deny("이미 군주입니다."), r)
    }

    @Test
    fun `allowRebellion denies when lord is active (killturn ge env killturn)`() {
        val c = allowRebellion()
        val r = c.test(
            cctxWithLordStaging(mapOf("__lordNo" to 42, "killturn" to 100, "__lordKillturn" to 100)),
            STATE_VIEW_STUB,
        )
        assertEquals(ConstraintResult.Deny("군주가 활동중입니다."), r)
    }

    @Test
    fun `allowRebellion denies when lord is npc (in 2,3,6,9)`() {
        val c = allowRebellion()
        for (npc in listOf(2, 3, 6, 9)) {
            val r = c.test(
                cctxWithLordStaging(mapOf("__lordNo" to 42, "killturn" to 100, "__lordKillturn" to 50, "__lordNpc" to npc)),
                STATE_VIEW_STUB,
            )
            assertEquals(ConstraintResult.Deny("군주가 NPC입니다."), r, "npc=$npc → deny")
        }
    }

    @Test
    fun `allowRebellion allows when lord inactive, not self, not npc`() {
        val c = allowRebellion()
        val r = c.test(
            cctxWithLordStaging(mapOf("__lordNo" to 42, "killturn" to 100, "__lordKillturn" to 50, "__lordNpc" to 0)),
            STATE_VIEW_STUB,
        )
        assertEquals(ConstraintResult.Allow, r)
    }

    companion object {
        /** test()가 view를 사용하지 않는 staging-seam 제약이므로 빈 MemoryStateView 스텁. */
        private val STATE_VIEW_STUB = opensamguk.logic.statview.MemoryStateView(
            generals = emptyMap(), cities = emptyMap(), nations = emptyMap(), env = emptyMap(),
        )
    }
}
