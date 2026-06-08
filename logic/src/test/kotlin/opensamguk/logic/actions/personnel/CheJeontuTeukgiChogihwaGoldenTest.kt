package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
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
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * che_전투특기초기화 (전투 특기 초기화) — draw-0 deterministic GoldenTest.
 *
 * 충실 포트: legacy/devsam-core/hwe/sammo/Command/General/che_전투특기초기화.php run() (php:73-111).
 *
 * 게이트 검증:
 *  - 0-draw: resolve() 는 RandUtil 에서 단 한 번도 draw 하지 않는다(NoRng 가 모든 draw 호출에 throw).
 *  - effect deltas: special2→'None', specage2→age+1, aux['prev_types_special2'] 에 현재 특기 append
 *    (+ availableSpecialWar(20) 다 돌면 [현재특기]로 리셋), lastTurn=전투 특기 초기화.
 *  - 비용 없음(getCost=[0,0]): gold/rice 불변.
 *  - 제약: special2!='None' → Allow; ='None'/부재 → Deny('특기가 없습니다.').
 *  - 로그 byte-exact: "<C>●</>{month}월:새로운 전투 특기를 가질 준비가 되었습니다. <1>{date}</>".
 */
class CheJeontuTeukgiChogihwaGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 7
    private val date = "09:12"
    private val nation = Nation(id = 1, level = 2, capitalCityId = 99)
    private val env = WorldEnv(year = 195, startYear = 184, develCost = 100)

    /** NoRng: 어떤 draw 호출이든 MustNotBeReachedException throw → 0-draw 게이트 단언. */
    private fun noRng() = RandUtil(NoRng())

    private fun general(
        special2: String? = "che_귀병",
        age: Int = 40,
        specage2: Int = 30,
        aux: Map<String, Any?>? = null,
        gold: Int = 5000,
        rice: Int = 5000,
    ): General {
        val meta = linkedMapOf<String, Any?>()
        if (special2 != null) meta["special2"] = special2
        meta["age"] = age
        meta["specage2"] = specage2
        if (aux != null) meta["aux"] = aux
        return General(
            id = 42, nationId = 1, cityId = 7,
            leadership = 60, strength = 60, intel = 60,
            injury = 0, experience = 0.0, dedication = 0.0, officerLevel = 0,
            gold = gold, rice = rice, meta = meta,
        )
    }

    private fun city() = City(
        id = 7, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 20000,
        agriculture = 1000, agricultureMax = 20000,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun action() = CheJeontuTeukgiChogihwa(pipeline)

    @Test
    fun `key name category and lottery token`() {
        val a = action()
        assertEquals("che_전투특기초기화", a.key)
        assertEquals("전투 특기 초기화", a.name)
        assertEquals("인사", a.category)
        assertEquals("전투 특기 초기화", a.lotteryActionName)
        assertTrue(a.reservable, "예약형 커맨드")
        assertEquals(emptyMap<String, Any?>(), a.parseArgs(mapOf("x" to 1)), "무인자 argTest")
    }

    private fun ctx() = ConstraintContext(actorId = 42, cityId = 7, nationId = 1, mode = ConstraintMode.FULL)

    private fun viewFor(g: General) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = mapOf(7 to city()),
        nations = mapOf(1 to nation),
        env = emptyMap(),
    )

    @Test
    fun `ReqGeneralValue constraint - special set allows, None denies with 특기가 없습니다`() {
        val c = action().buildConstraints(ctx())
        assertEquals(1, c.size, "single constraint (ReqGeneralValue)")
        assertEquals("ReqGeneralValue", c[0].name)

        // 특기 있음 → Allow
        val gWith = general(special2 = "che_귀병")
        assertEquals(ConstraintResult.Allow, c[0].test(ctx(), viewFor(gWith)))

        // 특기 'None' → Deny('특기가 없습니다.')
        val gNone = general(special2 = "None")
        val rNone = c[0].test(ctx(), viewFor(gNone))
        assertTrue(rNone is ConstraintResult.Deny && rNone.reason == "특기가 없습니다.",
            "None → Deny 특기가 없습니다.; got $rNone")

        // 특기 부재(meta 키 없음) → 기본 'None' → Deny
        val gAbsent = general(special2 = null)
        val rAbsent = c[0].test(ctx(), viewFor(gAbsent))
        assertTrue(rAbsent is ConstraintResult.Deny && rAbsent.reason == "특기가 없습니다.",
            "absent → Deny 특기가 없습니다.; got $rAbsent")
    }

    @Test
    fun `resolve clears special2, sets specage2 = age+1, appends prev_types, byte-exact log, 0-draw, no cost`() {
        val draft = GeneralActionDraft(general(special2 = "che_귀병", age = 40, specage2 = 30), city(), nation)
        val context = GeneralActionResolveContext(draft, noRng(), env, MONTH, date)
        action().resolve(context)

        val g = draft.general
        assertEquals("None", g.meta["special2"], "special2 → None")
        assertEquals(41, g.meta["specage2"], "specage2 = age(40)+1")
        assertEquals(40, g.meta["age"], "age unchanged")

        @Suppress("UNCHECKED_CAST")
        val aux = g.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val prev = aux["prev_types_special2"] as List<Any?>
        assertEquals(listOf("che_귀병"), prev, "prev_types_special2 = [현재 특기]")

        // 비용 없음(getCost=[0,0])
        assertEquals(5000, g.gold, "gold unchanged")
        assertEquals(5000, g.rice, "rice unchanged")

        assertEquals("전투 특기 초기화", g.lastTurn.command, "lastTurn=전투 특기 초기화")

        // 로그 byte-exact
        assertEquals(1, context.logs().size)
        assertEquals(
            "<C>●</>${MONTH}월:새로운 전투 특기를 가질 준비가 되었습니다. <1>$date</>",
            context.logs()[0],
        )
        assertTrue(context.globalActionLogs().isEmpty())
        assertTrue(context.plainLogs().isEmpty())
    }

    @Test
    fun `resolve appends onto existing prev_types preserving order`() {
        val existingAux = linkedMapOf<String, Any?>(
            "prev_types_special2" to listOf("che_신산", "che_환술"),
            "last발령" to 7,   // 다른 aux 키는 보존(insertion order)
        )
        val draft = GeneralActionDraft(
            general(special2 = "che_집중", age = 33, specage2 = 20, aux = existingAux), city(), nation)
        action().resolve(GeneralActionResolveContext(draft, noRng(), env, MONTH, date))

        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val prev = aux["prev_types_special2"] as List<Any?>
        assertEquals(listOf("che_신산", "che_환술", "che_집중"), prev, "append 현재특기 보존순서")
        assertEquals(7, aux["last발령"], "다른 aux 키 보존")
        assertEquals(34, draft.general.meta["specage2"], "specage2 = 33+1")
    }

    @Test
    fun `resolve resets prev_types to single current when list fills availableSpecialWar (20)`() {
        // prev_types 가 이미 19개 → 현재 특기 추가하면 20(=availableSpecialWar.size) → [현재특기]로 리셋.
        assertEquals(20, GameConst.availableSpecialWar.size, "전투특기 20종 — 리셋 임계값")
        val nineteen = GameConst.availableSpecialWar.take(19)
        val existingAux = linkedMapOf<String, Any?>("prev_types_special2" to nineteen)
        val draft = GeneralActionDraft(
            general(special2 = "che_척사", age = 50, specage2 = 40, aux = existingAux), city(), nation)
        action().resolve(GeneralActionResolveContext(draft, noRng(), env, MONTH, date))

        @Suppress("UNCHECKED_CAST")
        val aux = draft.general.meta["aux"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val prev = aux["prev_types_special2"] as List<Any?>
        assertEquals(listOf("che_척사"), prev, "20 도달 → [현재특기]로 리셋")
    }

    @Test
    fun `determinism - two runs equal`() {
        val a = GeneralActionDraft(general(), city(), nation)
        action().resolve(GeneralActionResolveContext(a, noRng(), env, MONTH, date))
        val b = GeneralActionDraft(general(), city(), nation)
        action().resolve(GeneralActionResolveContext(b, noRng(), env, MONTH, date))
        assertEquals(a.general.meta["special2"], b.general.meta["special2"])
        assertEquals(a.general.meta["specage2"], b.general.meta["specage2"])
        assertEquals(a.general.meta["aux"], b.general.meta["aux"])
    }
}
