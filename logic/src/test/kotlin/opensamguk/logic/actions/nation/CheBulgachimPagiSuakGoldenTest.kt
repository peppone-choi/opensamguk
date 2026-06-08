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
 * GOLDEN — che_불가침파기수락 ([CheBulgachimPagiSuak]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침파기수락.php` `run(RandUtil $rng)`는 RNG draw 0.
 *
 *  - **0-draw**: [NoRng]로 강제.
 *  - **diplomacy patch**: 양방향 통상(TRADE=2)/term=0.
 *  - **log set byte-exact**: 버퍼 가능한 3건(actor action PLAIN L166, actor globalAction L169, dest action
 *    PLAIN L174). 나머지 3건(generalHistory / globalHistory / dest generalHistory)은 sink 부재로
 *    [CheBulgachimPagiSuak] 주석에 byte-exact 보존. 로그 문자열 `{와}의`(josa 와/과 + literal '의') byte-exact.
 *
 * 이름: actor 국명='촉', dest 국명='오', actor 장수='관우'. '오'+'와'→'와', '촉'+'와'→'과', '관우'+'이'→'가'.
 */
class CheBulgachimPagiSuakGoldenTest {

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
        cheBulgachimPagiSuak(pipeline()).resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── diplomacy patch ────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve patches both directions to trade term zero`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimPagiSuak(pipeline()).resolve(ctx)
        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)
        assertEquals(1, cascade[0].me); assertEquals(5, cascade[0].you)
        assertEquals(DiplomacyState.TRADE, cascade[0].state); assertEquals(0, cascade[0].term)
        assertEquals(5, cascade[1].me); assertEquals(1, cascade[1].you)
        assertEquals(DiplomacyState.TRADE, cascade[1].state); assertEquals(0, cascade[1].term)
    }

    // ── log set byte-exact ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `actor action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimPagiSuak(pipeline()).resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // L166 PLAIN — '오'+'와'→'와' + literal '의'.
        assertEquals("<C>●</><D><b>오</b></>와의 불가침을 파기했습니다.", logs[0])
    }

    @Test
    fun `global action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimPagiSuak(pipeline()).resolve(ctx)
        val globals = ctx.globalActionLogs()
        assertEquals(1, globals.size)
        // L169 globalAction (MONTH). '관우'+'이'→'가', '오'+'와'→'와' + literal '의'.
        assertEquals(
            "<C>●</>3월:<Y>관우</>가 <D><b>오</b></>와의 불가침 조약을 <M>파기</> 하였습니다.",
            globals[0],
        )
    }

    @Test
    fun `dest action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimPagiSuak(pipeline()).resolve(ctx)
        val destLogs = ctx.plainLogsTo(42)
        assertEquals(1, destLogs.size)
        // L174 dest PLAIN — '촉'+'와'→'과' + literal '의'.
        assertEquals("<C>●</><D><b>촉</b></>과의 불가침 파기에 성공했습니다.", destLogs[0])
    }

    // ── constraints / args ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `constraints include reqDest and allow non-aggression only`() {
        val cmd = cheBulgachimPagiSuak(pipeline())
        val cctx = opensamguk.logic.constraints.ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = emptyMap(), args = mapOf("destNationID" to 5),
            destNationId = 5, mode = opensamguk.logic.constraints.ConstraintMode.FULL,
        )
        val constraints = cmd.buildConstraints(cctx)
        assertTrue(constraints.any { it.name == "ReqDestNationValue" })
        assertTrue(constraints.any { it.name == "AllowDiplomacyBetweenStatus" })
    }

    @Test
    fun `parseArgs keeps destNationID and destGeneralID`() {
        val cmd = cheBulgachimPagiSuak(pipeline())
        val parsed = cmd.parseArgs(mapOf("destNationID" to 5, "destGeneralID" to 42))
        assertEquals(5, parsed["destNationID"])
        assertEquals(42, parsed["destGeneralID"])
    }
}
