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
 * GOLDEN — che_불가침수락 ([CheBulgachimSuak]) draw COUNT = 0.
 *
 * PHP `legacy/devsam-core/hwe/sammo/Command/Nation/che_불가침수락.php` `run(RandUtil $rng)`는 RNG draw 0.
 *
 *  - **0-draw**: [NoRng]로 강제.
 *  - **term 공식 byte-exact**: PHP L203-204 — `currentMonth = env.year*12 + env.month - 1`,
 *    `reqMonth = year*12 + month`(−1 없음), `term = reqMonth - currentMonth`. (이전 orphaned의 off-by-one +
 *    6개월 가드는 PHP에 없어 제거됨 — run() 내부 하한 없음.)
 *  - **diplomacy patch**: 양방향 불가침(NON_AGGRESSION=7)/term.
 *  - **log set byte-exact**: 버퍼 가능한 2건(actor action PLAIN L220, dest action PLAIN L225). generalHistory
 *    2건은 sink 부재로 [CheBulgachimSuak] 주석에 byte-exact 보존.
 *
 * 시나리오: env.year=200,month=3 → currentMonth=2402. args year=201,month=9 → reqMonth=2421 → term=19.
 * 이름: actor 국명='촉', dest 국명='오'. '오'+'와'→'와', '촉'+'와'→'과'.
 */
class CheBulgachimSuakGoldenTest {

    private fun pipeline() = GeneralActionPipeline()

    private fun context(
        rng: RandUtil,
        args: Map<String, Any?> = mapOf("destNationID" to 5, "destGeneralID" to 42, "year" to 201, "month" to 9),
        destNationName: String = "오",
        actorNationName: String = "촉",
        envYear: Int = 200,
        month: Int = 3,
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
        val env = WorldEnv(year = envYear, startYear = 190, develCost = 100)
        return GeneralActionResolveContext(
            draft = draft, rng = rng, env = env, month = month, date = "12:34",
            args = args, generalName = "관우", destGeneralName = destNationName,
        )
    }

    // ── 0-draw ──────────────────────────────────────────────────────────────────────────────────
    @Test
    fun `resolve makes zero rng draws`() {
        cheBulgachimSuak(pipeline()).resolve(context(RandUtil(NoRng())))
    }

    @Test
    fun `a stray draw on the NoRng path throws`() {
        val ctx = context(RandUtil(NoRng()))
        assertFailsWith<MustNotBeReachedException> { ctx.rng.nextRangeInt(1, 5) }
    }

    // ── term 공식 + diplomacy patch ────────────────────────────────────────────────────────────────
    @Test
    fun `resolve patches both directions to non-aggression with php-exact term`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimSuak(pipeline()).resolve(ctx)

        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)
        // currentMonth=2402, reqMonth=2421 → term=19
        assertEquals(1, cascade[0].me); assertEquals(5, cascade[0].you)
        assertEquals(DiplomacyState.NON_AGGRESSION, cascade[0].state)
        assertEquals(19, cascade[0].term)
        assertEquals(5, cascade[1].me); assertEquals(1, cascade[1].you)
        assertEquals(DiplomacyState.NON_AGGRESSION, cascade[1].state)
        assertEquals(19, cascade[1].term)
    }

    @Test
    fun `term has no six-month floor (php run has no guard)`() {
        // env.year=200,month=3 → currentMonth=2402. year=200,month=4 → reqMonth=2404 → term=2 (<6, but applied).
        val ctx = context(
            RandUtil(NoRng()),
            args = mapOf("destNationID" to 5, "destGeneralID" to 42, "year" to 200, "month" to 4),
        )
        cheBulgachimSuak(pipeline()).resolve(ctx)
        val cascade = ctx.draft.cascadeDiplomacy
        assertEquals(2, cascade.size)
        assertEquals(2, cascade[0].term)
    }

    // ── log set byte-exact ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `actor action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimSuak(pipeline()).resolve(ctx)
        val logs = ctx.logs()
        assertEquals(1, logs.size)
        // L220 PLAIN — year=201,month=9, '오'+'와'→'와'.
        assertEquals(
            "<C>●</><D><b>오</b></>와 <C>201</>년 <C>9</>월까지 불가침에 성공했습니다.",
            logs[0],
        )
        // 불가침수락은 globalAction 로그가 없다(PHP run에 globalActionLog push 없음).
        assertTrue(ctx.globalActionLogs().isEmpty())
    }

    @Test
    fun `dest action log is byte-exact`() {
        val ctx = context(RandUtil(NoRng()))
        cheBulgachimSuak(pipeline()).resolve(ctx)
        val destLogs = ctx.plainLogsTo(42)
        assertEquals(1, destLogs.size)
        // L225 dest PLAIN — '촉'+'와'→'과'.
        assertEquals(
            "<C>●</><D><b>촉</b></>과 <C>201</>년 <C>9</>월까지 불가침에 성공했습니다.",
            destLogs[0],
        )
    }

    // ── constraints / args ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `constraints include supplied occupied reqDest and disallow war declaration`() {
        val cmd = cheBulgachimSuak(pipeline())
        val cctx = opensamguk.logic.constraints.ConstraintContext(
            actorId = 1, nationId = 1, cityId = 1,
            env = emptyMap(), args = mapOf("destNationID" to 5),
            destNationId = 5, mode = opensamguk.logic.constraints.ConstraintMode.FULL,
        )
        val constraints = cmd.buildConstraints(cctx)
        assertTrue(constraints.any { it.name == "OccupiedCity" })
        assertTrue(constraints.any { it.name == "SuppliedCity" })
        assertTrue(constraints.any { it.name == "ReqDestNationValue" })
        assertTrue(constraints.any { it.name == "DisallowDiplomacyBetweenStatus" })
    }

    @Test
    fun `parseArgs valid keeps all four args`() {
        val cmd = cheBulgachimSuak(pipeline())
        val parsed = cmd.parseArgs(mapOf("destNationID" to 5, "destGeneralID" to 42, "year" to 201, "month" to 9))
        assertEquals(5, parsed["destNationID"])
        assertEquals(42, parsed["destGeneralID"])
        assertEquals(201, parsed["year"])
        assertEquals(9, parsed["month"])
    }

    @Test
    fun `parseArgs invalid month returns empty`() {
        val cmd = cheBulgachimSuak(pipeline())
        assertTrue(cmd.parseArgs(mapOf("destNationID" to 5, "destGeneralID" to 42, "year" to 201, "month" to 13)).isEmpty())
    }
}
