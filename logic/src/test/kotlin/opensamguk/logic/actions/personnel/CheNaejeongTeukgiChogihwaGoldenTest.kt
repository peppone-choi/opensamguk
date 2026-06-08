package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port-faithful (deterministic / 0-draw) test for che_내정특기초기화
 * (`che_내정특기초기화.php` extends `che_전투특기초기화.php`).
 *
 * 골든 캡처 불필요: 결정적 커맨드(draw_count == 0)라 run() 본문을 verbatim 이식하고 0-draw + after-state
 * 델타 + 로그 byte를 단위 GoldenTest로 못 박는다(태스크 지시). 로그 문자열은 PHP run()(:104)에서 그대로
 * 복사: "새로운 내정 특기를 가질 준비가 되었습니다. <1>$date</>".
 *
 * 검증:
 *   - 0-draw: CountingRandUtil 데코레이터로 전달된 RNG가 한 번도 소모되지 않음을 단언(말미 unique 로터리는
 *     별도 RNG seam이라 resolve 스트림 밖).
 *   - effect 델타: special → 'None', specage → age+1, aux['prev_types_special'] append.
 *   - 한 바퀴 리셋: 이력 길이가 availableSpecialDomestic.size(8)에 도달하면 [현재특기] 로 리셋.
 *   - 로그 byte: MONTH 포맷 액션 로그.
 *   - 제약: ReqGeneralValue — special=='None' 이면 Deny("특기가 없습니다.").
 */
class CheNaejeongTeukgiChogihwaGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 6
    private val FIXTURE_HIDDEN_SEED = "00000000000000000000000000000000"
    private val env = WorldEnv(year = 240, startYear = 184, develCost = 120)
    private val date = "23:00"

    /** 6-토큰 PHP 시드 위에 올린 draw-카운팅 RandUtil — 어떤 draw 메서드든 카운터를 올린다(consume-neutral). */
    private class CountingRandUtil(inner: LiteHashDrbg) : RandUtil(inner) {
        var draws = 0
            private set
        override fun nextFloat1(): Double { draws++; return super.nextFloat1() }
        override fun nextRange(min: Double, max: Double): Double { draws++; return super.nextRange(min, max) }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int { draws++; return super.nextRangeInt(minInclusive, maxInclusive) }
        override fun nextInt(minInclusive: Int, maxExclusive: Int): Int { draws++; return super.nextInt(minInclusive, maxExclusive) }
        override fun nextBit(): Boolean { draws++; return super.nextBit() }
        override fun nextBool(prob: Double): Boolean { draws++; return super.nextBool(prob) }
        override fun <T> shuffle(srcArray: List<T>): List<T> { draws++; return super.shuffle(srcArray) }
        override fun <T> choice(items: List<T>): T { draws++; return super.choice(items) }
        override fun choiceUsingWeight(items: Map<String, Double>): String { draws++; return super.choiceUsingWeight(items) }
        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T { draws++; return super.choiceUsingWeightPair(items) }
    }

    private fun rng() = CountingRandUtil(
        LiteHashDrbg(serializeSeed(FIXTURE_HIDDEN_SEED, "generalCommand", 240, MONTH, 42, "che_내정특기초기화")))

    private fun general(
        special: String = "che_경작",
        age: Int = 40,
        aux: Map<String, Any?>? = null,
    ): General {
        val meta = linkedMapOf<String, Any?>(
            "name" to "관우", "age" to age, "special" to special, "specage" to 0,
        )
        if (aux != null) meta["aux"] = aux
        return General(
            id = 42, nationId = 7, cityId = 500,
            leadership = 70, strength = 64, intel = 80, injury = 0,
            experience = 1000.0, dedication = 2000.0,
            officerLevel = 5, gold = 1000, rice = 1000,
            meta = meta,
        )
    }

    private fun city() = City(
        id = 500, nationId = 7, level = 5,
        commerce = 1, commerceMax = 1, agriculture = 1, agricultureMax = 1,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private val nation = Nation(id = 7, level = 3, capitalCityId = 500, name = "촉")

    @Test
    fun `key name category and turn cooldown`() {
        val a = CheNaejeongTeukgiChogihwa(pipeline)
        assertEquals("che_내정특기초기화", a.key)
        assertEquals("내정 특기 초기화", a.name)
        // 형제 CheJeontuTeukgiChogihwa 와 동일 카테고리("인사"). PHP availableGeneralCommand 는 '개인'
        // 그룹이나, UI 그룹핑 필드는 골든-게이트 대상이 아니며 형제 정합성을 우선한다(REPORT 불확실 항목).
        assertEquals("인사", a.category)
    }

    @Test
    fun `constraint ReqGeneralValue denies when special is None`() {
        val a = CheNaejeongTeukgiChogihwa(pipeline)
        val constraints = a.buildConstraints(ConstraintContext(actorId = 42, nationId = 7, mode = ConstraintMode.FULL))
        assertEquals(listOf("ReqGeneralValue"), constraints.map { it.name }, "min/fullCondition = ReqGeneralValue only")

        val ctx = ConstraintContext(actorId = 42, nationId = 7, mode = ConstraintMode.FULL)
        // special == 'None' → Deny("특기가 없습니다.")
        val denyView = MemoryStateView(
            generals = mapOf(42 to general(special = "None")), cities = emptyMap(),
            nations = emptyMap(), env = emptyMap())
        val deny = constraints[0].test(ctx, denyView)
        assertTrue(deny is ConstraintResult.Deny, "None → Deny")
        assertEquals("특기가 없습니다.", (deny as ConstraintResult.Deny).reason, "errMsg byte-match")
        // special != 'None' → Allow
        val allowView = MemoryStateView(
            generals = mapOf(42 to general(special = "che_상재")), cities = emptyMap(),
            nations = emptyMap(), env = emptyMap())
        assertTrue(constraints[0].test(ctx, allowView) is ConstraintResult.Allow, "non-None → Allow")
    }

    @Test
    fun `resolve resets special to None bumps specage and appends history with 0 draws`() {
        val draft = GeneralActionDraft(general(special = "che_경작", age = 40), city(), nation)
        val r = rng()
        val ctx = GeneralActionResolveContext(draft, r, env, MONTH, date)

        CheNaejeongTeukgiChogihwa(pipeline).resolve(ctx)

        // 0-draw: 전달된 RNG는 resolve 본문에서 소모되지 않는다(말미 unique 로터리는 별도 RNG seam).
        assertEquals(0, r.draws, "resolver must consume 0 draws")

        val g = draft.general
        // special → 'None'
        assertEquals("None", g.meta["special"], "special reset to None")
        // specage → age + 1 (40 + 1 = 41)
        assertEquals(41, metaInt(g.meta, "specage"), "specage = age + 1")
        // age 불변
        assertEquals(40, metaInt(g.meta, "age"), "age unchanged")

        // aux['prev_types_special'] = [이전 특기] (이력 첫 append)
        @Suppress("UNCHECKED_CAST")
        val aux = g.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val hist = aux["prev_types_special"] as List<String>
        assertEquals(listOf("che_경작"), hist, "history appends prior special")

        // 로그 byte (MONTH 포맷): "<C>●</>6월:새로운 내정 특기를 가질 준비가 되었습니다. <1>23:00</>"
        assertEquals(
            listOf("<C>●</>${MONTH}월:새로운 내정 특기를 가질 준비가 되었습니다. <1>$date</>"),
            ctx.logs(),
            "action-log byte-match",
        )
        // 글로벌/PLAIN 로그 없음
        assertTrue(ctx.globalActionLogs().isEmpty(), "no global log")
        assertTrue(ctx.plainLogs().isEmpty(), "no plain log")
    }

    @Test
    fun `resolve appends onto an existing history list preserving order`() {
        val existingAux = linkedMapOf<String, Any?>(
            "prev_types_special" to listOf("che_상재", "che_발명"),
        )
        val draft = GeneralActionDraft(
            general(special = "che_축성", age = 55, aux = existingAux), city(), nation)
        val r = rng()
        val ctx = GeneralActionResolveContext(draft, r, env, MONTH, date)

        CheNaejeongTeukgiChogihwa(pipeline).resolve(ctx)

        assertEquals(0, r.draws, "0 draws")
        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val hist = aux["prev_types_special"] as List<String>
        // [상재, 발명] + 현재(축성) — 길이 3 < 8 이므로 리셋 없음, append-additive 순서 보존.
        assertEquals(listOf("che_상재", "che_발명", "che_축성"), hist, "append-additive order")
        assertEquals(56, metaInt(draft.general.meta, "specage"), "specage = 55 + 1")
    }

    @Test
    fun `resolve resets history to current special when the full cycle is exhausted`() {
        // 이미 7개 이력 + 현재 특기 = 8개 = availableSpecialDomestic.size → 리셋해 [현재] 만 남긴다.
        // (availableSpecialDomestic: 경작/상재/발명/축성/수비/통찰/인덕/귀모 — 8종)
        val seven = GameConst.availableSpecialDomestic.take(7)   // [경작..인덕]
        val current = "che_귀모"                                  // 8번째
        assertEquals(8, GameConst.availableSpecialDomestic.size, "내정특기 8종 가정")
        val existingAux = linkedMapOf<String, Any?>("prev_types_special" to seven)
        val draft = GeneralActionDraft(
            general(special = current, age = 60, aux = existingAux), city(), nation)
        val r = rng()
        val ctx = GeneralActionResolveContext(draft, r, env, MONTH, date)

        CheNaejeongTeukgiChogihwa(pipeline).resolve(ctx)

        assertEquals(0, r.draws, "0 draws")
        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val hist = aux["prev_types_special"] as List<String>
        // count(oldSpecialList)==8 → oldSpecialList = [현재 특기]
        assertEquals(listOf(current), hist, "history reset to [current] on full cycle")
        assertEquals("None", draft.general.meta["special"], "special still reset to None")
    }

    @Test
    fun `aux other keys are preserved when writing history`() {
        val existingAux = linkedMapOf<String, Any?>(
            "some_other_key" to 123,
            "prev_types_special" to listOf("che_상재"),
        )
        val draft = GeneralActionDraft(
            general(special = "che_발명", age = 30, aux = existingAux), city(), nation)
        val r = rng()
        val ctx = GeneralActionResolveContext(draft, r, env, MONTH, date)

        CheNaejeongTeukgiChogihwa(pipeline).resolve(ctx)

        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        assertEquals(123, aux["some_other_key"], "unrelated aux key preserved")
        @Suppress("UNCHECKED_CAST")
        val hist = aux["prev_types_special"] as List<String>
        assertEquals(listOf("che_상재", "che_발명"), hist, "history appended")
        assertFalse(r.draws > 0, "still 0 draws")
    }
}
