package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.military.CheSukryeonJeonhwan
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.event.StaticEventHandler
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.numberFormat
import opensamguk.logic.util.phpToInt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * che_숙련전환 byte/draw 게이트 (deterministic, draw-0) — golden은 PHP run()(che_숙련전환.php:159-185)에서
 * 직접 산출(도커 캡처 불요). 한 병종 숙련(dex)의 일부를 다른 병종 숙련으로 전환한다.
 *
 * 케이스: srcArmType=1(보병) dex1=350.0 → destArmType=3(기병) dex3=100.0, develcost=12.
 *   cutDex = toInt(350 * 0.4) = toInt(140.0)  = 140
 *   addDex = toInt(140 * 0.9) = toInt(126.0)  = 126
 *   dex1 = 350 - 140 = 210, dex3 = 100 + 126 = 226
 *   josa: pick("140","을") → 마지막 자리 0 → 받침 有 → "을";  pick("126","로") → 마지막 자리 6(=육, ㄱ받침,
 *         非ㄹ) → 받침 有 → "으로" (PHP JosaUtil::pick: 숫자 [178]만 "로", 6은 regSpecialChar "[^a-zA-Z][036]"
 *         매치로 받침 有 → with-jongsung "으로". 한글 grand truth.)
 *   로그 body = "보병 숙련 140을 기병 숙련 126으로 전환했습니다. <1>12:00</>"
 *   addLog 래핑 = "<C>●</>1월:" + body
 *   gold 5000-12=4988, rice 4000-12=3988, leadership_exp 0+2=2, experience 0+10=10(레벨無), checkStatChange 무변동.
 *
 * draw-0: run()은 RNG를 소비하지 않는다 → 어떤 draw 메서드라도 호출되면 throw하는 DrawGuardRng로 0-draw 강제.
 */
class CheSukryeonJeonhwanGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val develCost = 12

    @AfterTest
    fun clearStaticHandlers() = StaticEventHandler.clear()

    /** 어떤 추첨도 호출되면 즉시 실패시키는 RNG — run()의 0-draw 불변식 강제. */
    private class DrawGuardRng : RandUtil(LiteHashDrbg("00")) {
        private fun fail(m: String): Nothing = throw AssertionError("che_숙련전환은 draw-0이어야 한다: $m 호출됨")
        override fun nextFloat1(): Double = fail("nextFloat1")
        override fun nextRange(min: Double, max: Double): Double = fail("nextRange")
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int = fail("nextRangeInt")
        override fun nextInt(minInclusive: Int, maxExclusive: Int): Int = fail("nextInt")
        override fun nextBit(): Boolean = fail("nextBit")
        override fun nextBool(prob: Double): Boolean = fail("nextBool")
        override fun <T> shuffle(srcArray: List<T>): List<T> = fail("shuffle")
        override fun <T> choice(items: List<T>): T = fail("choice")
        override fun choiceUsingWeight(items: Map<String, Double>): String = fail("choiceUsingWeight")
        override fun <T> choiceUsingWeightPair(items: List<Pair<T, Double>>): T = fail("choiceUsingWeightPair")
    }

    private fun actor() = General(
        id = 100, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 5000, rice = 4000,
        meta = linkedMapOf(
            "name" to "테스트장수",
            "dex1" to 350.0,        // 보병 숙련 (src)
            "dex3" to 100.0,        // 기병 숙련 (dest)
            "explevel" to 0,
            "leadership_exp" to 0.0,
            "strength_exp" to 0.0,
            "intel_exp" to 0.0,
        ),
    )

    private fun city() = City(
        id = 5, nationId = 1, level = 5,
        commerce = 1000, commerceMax = 2000,
        agriculture = 1000, agricultureMax = 2000,
        supplyState = 1, frontState = 0, trust = 100.0,
    )

    private fun nation() = Nation(id = 1, level = 5, capitalCityId = 5, gold = 100000, rice = 100000)

    private fun newCtx(draft: GeneralActionDraft) = GeneralActionResolveContext(
        draft = draft,
        rng = DrawGuardRng(),
        env = WorldEnv(year = 200, startYear = 180, develCost = develCost),
        month = 1,
        date = "12:00",
        args = linkedMapOf("srcArmType" to 1, "destArmType" to 3),
        generalName = "테스트장수",
    )

    @Test
    fun `dex transfer byte-matches the PHP run body with zero draws`() {
        val def = CheSukryeonJeonhwan(pipeline).withArg(linkedMapOf("srcArmType" to 1, "destArmType" to 3))
        assertEquals("che_숙련전환", def.key, "registry key (WireGate가 바인딩할 키)")

        val draft = GeneralActionDraft(actor(), city(), nation())
        val ctx = newCtx(draft)
        def.resolve(ctx)   // DrawGuardRng — 추첨 호출 시 즉시 throw → 0-draw 보장

        // --- 로그 byte-match (단일 전환 라인, addLog MONTH 래핑; PLAIN/레벨 로그 없음) ---
        val body = "보병 숙련 140을 기병 숙련 126으로 전환했습니다. <1>12:00</>"
        assertEquals(listOf("<C>●</>1월:$body"), ctx.logs(), "[숙련전환] action-log byte-match")
        assertEquals(emptyList(), ctx.plainLogs(), "[숙련전환] PLAIN 로그 없음(레벨/스탯 무변동)")
        assertEquals(emptyList(), ctx.globalActionLogs(), "[숙련전환] broadcast 없음")

        // --- dex 이전 델타 ---
        assertEquals(210.0, metaDouble(draft.general.meta, "dex1"), "[숙련전환] src dex 차감 (350-140)")
        assertEquals(226.0, metaDouble(draft.general.meta, "dex3"), "[숙련전환] dest dex 가산 (100+126)")

        // --- 자원/경험 델타 ---
        assertEquals(5000 - develCost, draft.general.gold, "[숙련전환] gold -= develcost")
        assertEquals(4000 - develCost, draft.general.rice, "[숙련전환] rice -= develcost")
        assertEquals(10.0, draft.general.experience, "[숙련전환] addExperience(10)")
        assertEquals(2.0, metaDouble(draft.general.meta, "leadership_exp"), "[숙련전환] leadership_exp += 2")

        // --- 레벨/스탯 무변동 ---
        assertEquals(70, draft.general.leadership, "[숙련전환] leadership 무변동")
        assertEquals(60, draft.general.strength, "[숙련전환] strength 무변동")
        assertEquals(50, draft.general.intel, "[숙련전환] intel 무변동")

        // --- lastTurn 기록 ---
        assertEquals("숙련전환", draft.general.lastTurn.command, "[숙련전환] lastTurn command")
    }

    @Test
    fun `tail reaches StaticEventHandler after lastTurn and publishes unique intent without drawing action rng`() {
        val observed = mutableListOf<String>()
        StaticEventHandler.register("che_숙련전환") { general, _, _, params ->
            observed += "${general.lastTurn.command}:${params["srcArmType"]}:${params["destArmType"]}"
        }

        val def = CheSukryeonJeonhwan(pipeline).withArg(linkedMapOf("srcArmType" to 1, "destArmType" to 3))
        val draft = GeneralActionDraft(actor(), city(), nation())
        def.resolve(newCtx(draft))

        assertEquals(listOf("숙련전환:1:3"), observed)
        assertEquals("숙련전환", def.lastUniqueLotteryIntent?.seedReason)
        assertEquals("아이템", def.lastUniqueLotteryIntent?.acquireType)
        assertEquals("setResultTurn>checkStatChange>StaticEventHandler", def.lastUniqueLotteryIntent?.afterTail)
    }

    @Test
    fun `cut and add values match toInt truncation and number_format`() {
        // 절단(toInt) 검증: 357 * 0.4 = 142.8 → 142;  142 * 0.9 = 127.8 → 127.
        val cut = phpToInt(357.0 * 0.4)
        val add = phpToInt(cut.toDouble() * 0.9)
        assertEquals(142, cut, "cutDex = toInt(357*0.4)")
        assertEquals(127, add, "addDex = toInt(142*0.9)")
        // number_format(콤마 구분).
        assertEquals("1,234", numberFormat(1234), "number_format 콤마 그룹")
    }

    @Test
    fun `argTest requires PHP is_int arm types`() {
        val def = CheSukryeonJeonhwan(pipeline)

        assertEquals(linkedMapOf("srcArmType" to 1, "destArmType" to 3), def.parseArgs(linkedMapOf("srcArmType" to 1, "destArmType" to 3)))
        assertTrue(def.parseArgs(linkedMapOf("srcArmType" to "1", "destArmType" to 3)).isEmpty())
        assertTrue(def.parseArgs(linkedMapOf("srcArmType" to 1.0, "destArmType" to 3)).isEmpty())
        assertTrue(def.parseArgs(linkedMapOf("srcArmType" to 1, "destArmType" to 1)).isEmpty())
    }
}
