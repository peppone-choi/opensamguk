package opensamguk.logic.actions.nation

import opensamguk.common.rng.MustNotBeReachedException
import opensamguk.common.rng.NoRng
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * GOLDEN — che_종전수락 ([CheJongjeonSuak]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/Nation/che_종전수락.php` `run(RandUtil $rng)`는 RNG를 단 한 번도
 * 끌지 않는다(draw COUNT = 0). docker 골든 불필요 — 본 테스트가 골든을 대신한다:
 *
 *  - **0-draw**: [NoRng]-backed [RandUtil]로 resolve. 한 번이라도 draw하면 [MustNotBeReachedException].
 *  - **diplomacy patch**: 양방향 통상(TRADE=2)/term=0 (forward me→you, reverse you→me).
 *  - **log set byte-exact**: 현 [GeneralActionResolveContext] sink이 버퍼하는 3건(actor action PLAIN L177,
 *    actor globalAction L180, dest action PLAIN L187)을 byte-exact + 순서로 검증한다. 나머지 5건(history
 *    스코프)은 sink 부재로 [CheJongjeonSuak] 주석에 byte-exact 보존(WAVE-4 이연, 날조/약화 없음).
 *
 * 이름: actor 국명='촉', dest 국명='오'(어댑터가 destGeneralName으로 dest 국명 주입 — 제의 sibling 패턴 동일).
 * josa: '오'+'와'→'와'(받침 없음), '관우'+'이'→'가'(받침 없음), '촉'+'와'→'과'(받침 ㄱ).
 */
class CheJongjeonSuakGoldenTest {

    private fun pipeline() = GeneralActionPipeline()

    private fun context(
        rng: RandUtil,
        args: Map<String, Any?> = mapOf("destNationID" to 5, "destGeneralID" to 42),
        generalName: String = "관우",
        destNationName: String = "오",
        actorNationName: String = "촉",
        withDestGeneral: Boolean = true,
    ): GeneralActionResolveContext {
        val general = General(
            id = 7, nationId = 1, cityId = 1,
            leadership = 80, strength = 80, intel = 80, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
        )
        val city = City(
            id = 1, nationId = 1, level = 5,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = 1, frontState = 0, trust = 50.0,
        )
        val nation = Nation(id = 1, level = 5, capitalCityId = 1, name = actorNationName, color = "#00ff00")
        val draft = GeneralActionDraft(general = general, city = city, nation = nation)
        if (withDestGeneral) {
            // dest 장수(어댑터가 draft.destGeneral로 주입; addPlainLogTo의 타겟 id 출처 — ChePosang 패턴).
            draft.destGeneral = General(
                id = 42, nationId = 5, cityId = 30,
                leadership = 70, strength = 70, intel = 70, injury = 0,
                experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 0, rice = 0,
            )
        }
        val env = WorldEnv(year = 200, startYear = 190, develCost = 100)
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = 3, date = "12:34",
            args = args, generalName = generalName, destGeneralName = destNationName,
        )
    }

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws`() {
        cheJongjeonSuak(pipeline()).resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── diplomacy patch: 양방향 통상/term=0 ─────────────────────────────────────────────────────────
    @Test
    fun `resolve patches both directions to trade term zero`() {
        val ctx = context(RandUtil(NoRng()))
        cheJongjeonSuak(pipeline()).resolve(ctx)

        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)
        // forward me=1 → you=5
        assertEquals(1, cascade[0].me)
        assertEquals(5, cascade[0].you)
        assertEquals(DiplomacyState.TRADE, cascade[0].state)
        assertEquals(0, cascade[0].term)
        // reverse me=5 → you=1
        assertEquals(5, cascade[1].me)
        assertEquals(1, cascade[1].you)
        assertEquals(DiplomacyState.TRADE, cascade[1].state)
        assertEquals(0, cascade[1].term)
    }

    // ── log set byte-exact (버퍼 가능한 3건 + 순서) ────────────────────────────────────────────────
    @Test
    fun `actor action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheJongjeonSuak(pipeline()).resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // L177 generalAction PLAIN → '<C>●</>' + body. '오'+'와'→'와'.
        assertEquals("<C>●</><D><b>오</b></>와 종전에 합의했습니다.", logs[0])
    }

    @Test
    fun `global action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheJongjeonSuak(pipeline()).resolve(ctx)
        val globals = ctx.globalActionLogs()
        assertEquals(1, globals.size)
        // L180 globalAction (MONTH 프리픽스 '<C>●</>{month}월:'). '관우'+'이'→'가', '오'+'와'→'와'.
        assertEquals(
            "<C>●</>3월:<Y>관우</>가 <D><b>오</b></>와 <M>종전 합의</> 하였습니다.",
            globals[0],
        )
    }

    @Test
    fun `dest action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheJongjeonSuak(pipeline()).resolve(ctx)
        // L187 dest generalAction PLAIN — dest 장수 id=42 스코프. '촉'+'와'→'과'.
        val destLogs = ctx.plainLogsTo(42)
        assertEquals(1, destLogs.size)
        assertEquals("<C>●</><D><b>촉</b></>과 종전에 성공했습니다.", destLogs[0])
    }

    @Test
    fun `dest action log absent when adapter did not inject dest general`() {
        val ctx = context(RandUtil(NoRng()), withDestGeneral = false)
        cheJongjeonSuak(pipeline()).resolve(ctx)
        assertTrue(ctx.plainLogsTo(42).isEmpty())
        // diplomacy patch는 dest 장수 유무와 무관하게 적용된다.
        assertEquals(2, ctx.draft.cascadeDiplomacy.size)
    }

    // ── constraints ───────────────────────────────────────────────────────────────────────────────
    @Test
    fun `constraints include reqDestNationValue and allow war declaration only`() {
        val cmd = cheJongjeonSuak(pipeline())
        val cctx = opensamguk.logic.constraints.ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = emptyMap(), args = mapOf("destNationID" to 5),
            destNationId = 5, mode = opensamguk.logic.constraints.ConstraintMode.FULL,
        )
        val constraints = cmd.buildConstraints(cctx)
        assertTrue(constraints.any { it.name == "ReqDestNationValue" })
        assertTrue(constraints.any { it.name == "AllowDiplomacyBetweenStatus" })
        assertTrue(constraints.any { it.name == "ExistsDestGeneral" })
    }

    @Test
    fun `parseArgs keeps destNationID and destGeneralID`() {
        val cmd = cheJongjeonSuak(pipeline())
        val parsed = cmd.parseArgs(mapOf("destNationID" to 5, "destGeneralID" to 42))
        assertEquals(5, parsed["destNationID"])
        assertEquals(42, parsed["destGeneralID"])
    }

    @Test
    fun `parseArgs rejects self-target dest general id missing`() {
        val cmd = cheJongjeonSuak(pipeline())
        assertTrue(cmd.parseArgs(mapOf("destNationID" to 5)).isEmpty())
        assertTrue(cmd.parseArgs(mapOf("destNationID" to 0, "destGeneralID" to 42)).isEmpty())
    }
}
