package opensamguk.logic.divergence

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.nation.CheBulgachimPagiSuak
import opensamguk.logic.actions.nation.CheBulgachimSuak
import opensamguk.logic.actions.nation.CheJongjeonSuak
import opensamguk.logic.actions.nation.DiplomacyDivergence
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DIVERGENCE TEST — 외교 수락 정치력 게이트 (fiveStatLogic 플래그).
 *
 * **NO PHP GOLDEN ORACLE.** devsam/core(grand truth)는 3-stat이라 정치(politics) 컬럼이 없으므로 이 게이트는
 * 캡처된 PHP 픽스처로 재생할 수 없다. 아래 단언은 순수 BEHAVIORAL(상대적: 게이트 발동 여부)이며, 플래그-게이트
 * 스위치를 증명한다:
 *   - 플래그 OFF(기본) → 외교 수락이 정상 진행되어 cascadeDiplomacy가 채워진다(패러티 baseline byte-identical),
 *   - 플래그 ON + 수락측 정치력 < [DiplomacyDivergence.POLITICS_DIPLOMACY_BAR] → 수락 실패:
 *     diplomacy 상태 변경 없음(cascadeDiplomacy 비어 있음) + 한글 실패 로그 존재,
 *   - 플래그 ON + 수락측 정치력 >= bar → 정상 성공.
 *
 * 구성은 [opensamguk.logic.actions.nation.DiplomacyAcceptCommandsTest]의 픽스처를 그대로 따르고,
 * 플래그/정치력만 파라미터화한다. 외교 수락 3종은 draw 0(RNG 미소비)이라 RNG는 무관하다.
 */
class DiplomacyPoliticsGateDivergenceTest {

    private fun pipeline() = GeneralActionPipeline()

    /** 수락측 장수 — politics만 파라미터화(나머지는 DiplomacyAcceptCommandsTest 픽스처와 동일). */
    private fun general(politics: Int) = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12, gold = 1000, rice = 1000,
        politics = politics,
    )

    private fun city() = City(
        id = 1, nationId = 1, level = 5,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private val nation = Nation(id = 1, level = 5, capitalCityId = 1, name = "테스트국")

    private fun makeContext(politics: Int, flag: Boolean, args: Map<String, Any?>): GeneralActionResolveContext {
        val draft = GeneralActionDraft(general = general(politics), city = city(), nation = nation)
        val rng = RandUtil(LiteHashDrbg(ByteArray(0)))
        val env = WorldEnv(year = 2025, startYear = 190, develCost = 100, fiveStatLogic = flag)
        return GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = env,
            month = 6,
            date = "12:00",
            args = args,
            generalName = "테스트장수",
            destGeneralName = "상대국",
        )
    }

    private val belowBar = DiplomacyDivergence.POLITICS_DIPLOMACY_BAR - 1   // 29 < 30
    private val atBar = DiplomacyDivergence.POLITICS_DIPLOMACY_BAR          // 30 >= 30

    // ========================================================================
    // 종전 수락
    // ========================================================================

    @Test
    fun `DIVERGENCE 종전수락 — flag OFF applies diplomacy even when politics is low (baseline)`() {
        val ctx = makeContext(politics = belowBar, flag = false, args = mapOf("destNationID" to 5))
        CheJongjeonSuak(pipeline()).resolve(ctx)
        // 플래그 OFF → 정치력 무관하게 정상 진행 → 양방향 diplomacy 적재(패러티 baseline).
        assertEquals(2, ctx.draft.cascadeDiplomacy.size, "flag OFF: diplomacy applies regardless of politics")
    }

    @Test
    fun `DIVERGENCE 종전수락 — flag ON + low politics fails (no diplomacy change + failure log)`() {
        val ctx = makeContext(politics = belowBar, flag = true, args = mapOf("destNationID" to 5))
        CheJongjeonSuak(pipeline()).resolve(ctx)
        // 수락 실패 → diplomacy 상태 변경 없음 + 전역 로그 없음.
        assertTrue(ctx.draft.cascadeDiplomacy.isEmpty(), "flag ON + low politics: no diplomacy change")
        assertTrue(ctx.globalActionLogs().isEmpty(), "flag ON + low politics: no global 종전 합의 broadcast")
        // 한글 실패 로그 존재(divergence 텍스트).
        assertTrue(
            ctx.logs().any { it.contains(DiplomacyDivergence.POLITICS_FAIL_LOG) },
            "flag ON + low politics: failure log present (logs=${ctx.logs()})",
        )
    }

    @Test
    fun `DIVERGENCE 종전수락 — flag ON + politics at bar still succeeds`() {
        val ctx = makeContext(politics = atBar, flag = true, args = mapOf("destNationID" to 5))
        CheJongjeonSuak(pipeline()).resolve(ctx)
        // 정치력 >= bar → 정상 성공 → 양방향 diplomacy 적재 + 실패 로그 부재.
        assertEquals(2, ctx.draft.cascadeDiplomacy.size, "flag ON + politics>=bar: diplomacy applies")
        assertTrue(
            ctx.logs().none { it.contains(DiplomacyDivergence.POLITICS_FAIL_LOG) },
            "flag ON + politics>=bar: no failure log",
        )
    }

    // ========================================================================
    // 불가침 수락
    // ========================================================================

    @Test
    fun `DIVERGENCE 불가침수락 — flag OFF applies diplomacy when politics low (baseline)`() {
        val ctx = makeContext(
            politics = belowBar, flag = false,
            args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3),
        )
        CheBulgachimSuak(pipeline()).resolve(ctx)
        assertEquals(2, ctx.draft.cascadeDiplomacy.size, "flag OFF: 불가침 diplomacy applies regardless of politics")
    }

    @Test
    fun `DIVERGENCE 불가침수락 — flag ON + low politics fails (no diplomacy + failure log)`() {
        val ctx = makeContext(
            politics = belowBar, flag = true,
            args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3),
        )
        CheBulgachimSuak(pipeline()).resolve(ctx)
        assertTrue(ctx.draft.cascadeDiplomacy.isEmpty(), "flag ON + low politics: no 불가침 diplomacy change")
        assertTrue(
            ctx.logs().any { it.contains(DiplomacyDivergence.POLITICS_FAIL_LOG) },
            "flag ON + low politics: failure log present (logs=${ctx.logs()})",
        )
    }

    @Test
    fun `DIVERGENCE 불가침수락 — flag ON + politics at bar still succeeds`() {
        val ctx = makeContext(
            politics = atBar, flag = true,
            args = mapOf("destNationID" to 5, "year" to 2026, "month" to 3),
        )
        CheBulgachimSuak(pipeline()).resolve(ctx)
        assertEquals(2, ctx.draft.cascadeDiplomacy.size, "flag ON + politics>=bar: 불가침 diplomacy applies")
        assertTrue(
            ctx.logs().none { it.contains(DiplomacyDivergence.POLITICS_FAIL_LOG) },
            "flag ON + politics>=bar: no failure log",
        )
    }

    // ========================================================================
    // 불가침 파기 수락
    // ========================================================================

    @Test
    fun `DIVERGENCE 불가침파기수락 — flag OFF applies diplomacy when politics low (baseline)`() {
        val ctx = makeContext(politics = belowBar, flag = false, args = mapOf("destNationID" to 5))
        CheBulgachimPagiSuak(pipeline()).resolve(ctx)
        assertEquals(2, ctx.draft.cascadeDiplomacy.size, "flag OFF: 파기 diplomacy applies regardless of politics")
    }

    @Test
    fun `DIVERGENCE 불가침파기수락 — flag ON + low politics fails (no diplomacy + failure log)`() {
        val ctx = makeContext(politics = belowBar, flag = true, args = mapOf("destNationID" to 5))
        CheBulgachimPagiSuak(pipeline()).resolve(ctx)
        assertTrue(ctx.draft.cascadeDiplomacy.isEmpty(), "flag ON + low politics: no 파기 diplomacy change")
        assertTrue(ctx.globalActionLogs().isEmpty(), "flag ON + low politics: no global 파기 broadcast")
        assertTrue(
            ctx.logs().any { it.contains(DiplomacyDivergence.POLITICS_FAIL_LOG) },
            "flag ON + low politics: failure log present (logs=${ctx.logs()})",
        )
    }

    @Test
    fun `DIVERGENCE 불가침파기수락 — flag ON + politics at bar still succeeds`() {
        val ctx = makeContext(politics = atBar, flag = true, args = mapOf("destNationID" to 5))
        CheBulgachimPagiSuak(pipeline()).resolve(ctx)
        assertEquals(2, ctx.draft.cascadeDiplomacy.size, "flag ON + politics>=bar: 파기 diplomacy applies")
        assertTrue(
            ctx.logs().none { it.contains(DiplomacyDivergence.POLITICS_FAIL_LOG) },
            "flag ON + politics>=bar: no failure log",
        )
    }
}
