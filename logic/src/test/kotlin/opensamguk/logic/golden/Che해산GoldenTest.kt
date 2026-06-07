package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.founding.CheHaesan
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AREA GATE-RUNTIME (A2 / EXECUTION_PLAN line 417) — che_해산 (군주 세력 해산) byte gate.
 *
 * che_해산 은 **draw 0** (추첨/RNG 없음). 따라서 골든 캡처 없이, run() 본문(che_해산.php:62-119) +
 * deleteNation cascade(func.php:1713-1805) 를 직접 이식한 deterministic 효과/로그를 byte-assert 한다.
 * 로그 문자열은 PHP run() 에서 verbatim 복사.
 *
 * 검증:
 *  (1) 0-draw: [RecordingRng] 으로 어떤 draw 메서드도 호출되지 않음 + DRBG 커서(stateIdx/bufferIdx) byte-동일.
 *  (2) <init-turn 가드(sameMonthOrBefore=true): "다음 턴부터 해산할 수 있습니다." + alternative=che_인재탐색
 *      + 쓰기 없음(early-return).
 *  (3) 정상 해산: gold/rice 절삭(레거시 rice 버그 포함) + cascade(전 장수 재야/도시 공백지) + makelimit=12
 *      + 로그(general action / global action / PLAIN 멸망) byte + deletedNationId.
 */
class Che해산GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val MONTH = 6
    private val date = "08:00"
    private val env = WorldEnv(year = 200, startYear = 184, develCost = 120)

    /**
     * 실제 [LiteHashDrbg] 위의 draw-기록 RandUtil — 어떤 overridable draw 메서드 호출도 [drawCount] 증가.
     * che_해산 은 0-draw 이므로 drawCount 는 0 이어야 하고, DRBG 커서도 호출 전후 byte-동일해야 한다.
     */
    private class RecordingRng(val drbg: LiteHashDrbg) : RandUtil(drbg) {
        var drawCount = 0
        override fun nextFloat1(): Double { drawCount++; return super.nextFloat1() }
        override fun nextRange(min: Double, max: Double): Double { drawCount++; return super.nextRange(min, max) }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int { drawCount++; return super.nextRangeInt(minInclusive, maxInclusive) }
        override fun nextInt(minInclusive: Int, maxExclusive: Int): Int { drawCount++; return super.nextInt(minInclusive, maxExclusive) }
        override fun nextBit(): Boolean { drawCount++; return super.nextBit() }
        override fun nextBool(prob: Double): Boolean { drawCount++; return super.nextBool(prob) }
        override fun <T> choice(items: List<T>): T { drawCount++; return super.choice(items) }
    }

    private fun lord(gold: Int, rice: Int) = General(
        id = 1, nationId = 7, cityId = 500,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 12, gold = gold, rice = rice,
        npcType = 0,
        meta = linkedMapOf("name" to "조조", "officer_city" to 500, "makelimit" to 0, "belong" to 5),
    )

    private fun member(id: Int, gold: Int, rice: Int, officerLevel: Int, npcType: Int = 0, belong: Int = 3) = General(
        id = id, nationId = 7, cityId = 501,
        leadership = 50, strength = 50, intel = 50, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel, gold = gold, rice = rice,
        troop = 99, npcType = npcType, officerCity = 501,
        meta = linkedMapOf("officer_city" to 501, "belong" to belong, "permission" to "auditor"),
    )

    private fun cityOf(id: Int, frontState: Int) = City(
        id = id, nationId = 7, level = 5,
        commerce = 1, commerceMax = 1, agriculture = 1, agricultureMax = 1,
        supplyState = 1, frontState = frontState, trust = 50.0,
    )

    private fun nation() = Nation(
        id = 7, level = 3, capitalCityId = 500, name = "위", color = "#abcdef",
        typeCode = "che_병가", tech = 1234.0, gold = 50000, rice = 50000,
    )

    private fun ctx(draft: GeneralActionDraft, rng: RandUtil, args: Map<String, Any?> = emptyMap()) =
        GeneralActionResolveContext(draft, rng, env, MONTH, date, args = args, generalName = "조조")

    // ----------------------------------------------------------------------------------------------
    // (2) <init-turn 가드 — sameMonthOrBefore=true → alternative=che_인재탐색 + early-return (no write).
    // ----------------------------------------------------------------------------------------------
    @Test
    fun `init-turn guard pushes the 인재탐색 alternative and writes nothing — draw 0`() {
        val drbg = LiteHashDrbg("che_해산-guard")
        val rng = RecordingRng(drbg)
        val s0 = drbg.peekStateIdx(); val b0 = drbg.peekBufferIdx()

        val draft = GeneralActionDraft(lord(gold = 9999, rice = 9999), cityOf(500, 1), nation())
        val cmd = CheHaesan(pipeline)
        val context = ctx(draft, rng, args = linkedMapOf("sameMonthOrBefore" to true))
        cmd.resolve(context)

        assertEquals(0, rng.drawCount, "che_해산 init-turn guard draws nothing")
        assertEquals(s0, drbg.peekStateIdx(), "DRBG stateIdx cursor byte-identical (no draw)")
        assertEquals(b0, drbg.peekBufferIdx(), "DRBG bufferIdx cursor byte-identical (no draw)")

        assertEquals(
            listOf("<C>●</>${MONTH}월:다음 턴부터 해산할 수 있습니다. <1>$date</>"),
            context.logs(),
            "guard action-log byte-match",
        )
        assertEquals("che_인재탐색", cmd.lastAlternative, "alternative = che_인재탐색")
        assertNull(cmd.lastDeletedNationId, "no nation deleted on guard")
        // no write: 군주 자원/소속 불변.
        assertEquals(9999, draft.general.gold, "guard: lord gold untouched")
        assertEquals(7, draft.general.nationId, "guard: lord still in nation")
        assertTrue(context.globalActionLogs().isEmpty(), "guard emits no global action log")
    }

    // ----------------------------------------------------------------------------------------------
    // (1)+(3) 정상 해산 — 0-draw + 절삭(레거시 rice 버그) + cascade + makelimit=12 + 로그 byte.
    // ----------------------------------------------------------------------------------------------
    @Test
    fun `disband byte-matches the PHP run — 0-draw, gold-rice cut with legacy rice bug, cascade, logs`() {
        val drbg = LiteHashDrbg("che_해산-run")
        val rng = RecordingRng(drbg)
        val s0 = drbg.peekStateIdx(); val b0 = drbg.peekBufferIdx()

        // 군주 gold 5000(>1000)/rice 5000(>1000) — 둘 다 절삭됨(php:97-98 increaseVarWithLimit).
        // 멤버 m17: gold 4000(>1000)→절삭, rice 4000 — 레거시 버그로 절삭 안 됨(gold 절삭후 ≤1000 → rice UPDATE 0행).
        // 멤버 m9 : gold 500(≤1000)→불변, rice 4000 — gold 미절삭 + rice 불변.
        val m17 = member(id = 17, gold = 4000, rice = 4000, officerLevel = 5, npcType = 0, belong = 8)
        val m9 = member(id = 9, gold = 500, rice = 4000, officerLevel = 4, npcType = 3, belong = 2)
        val draft = GeneralActionDraft(lord(gold = 5000, rice = 5000), cityOf(500, frontState = 1), nation()).apply {
            // 어댑터 선적재 순서 = ascending PK (멤버; 군주는 cascade에 미포함, 자기 step LAST).
            cascadeGenerals.add(m9)
            cascadeGenerals.add(m17)
            cascadeCities.add(cityOf(500, frontState = 1))
            cascadeCities.add(cityOf(501, frontState = 3))
            // diplomacy(me OR you) 는 deleteNation 이 DELETE 하므로 cascade-mutate 가 아니라
            // [CheHaesan.lastDeletedNationId] 엔진 tombstone seam 으로 처리된다(여기선 미적재).
        }

        val cmd = CheHaesan(pipeline)
        val context = ctx(draft, rng)
        cmd.resolve(context)

        // (1) 0-draw + DRBG 커서 byte-동일.
        assertEquals(0, rng.drawCount, "che_해산 draws nothing")
        assertEquals(s0, drbg.peekStateIdx(), "DRBG stateIdx byte-identical (0-draw)")
        assertEquals(b0, drbg.peekBufferIdx(), "DRBG bufferIdx byte-identical (0-draw)")

        // --- 군주 gold/rice 절삭 [0,1000] (php:97-98) ---
        assertEquals(1000, draft.general.gold, "lord gold cut to defaultGold")
        assertEquals(1000, draft.general.rice, "lord rice cut to defaultRice")

        // --- 멤버 gold 절삭(>1000) / rice 레거시 버그(절삭 안 됨) ---
        val af17 = draft.cascadeGenerals.first { it.id == 17 }
        assertEquals(1000, af17.gold, "m17 gold>1000 → defaultGold")
        assertEquals(4000, af17.rice, "m17 rice UNTOUCHED (legacy rice bug: gated on gold>defaultRice, 0 rows)")
        val af9 = draft.cascadeGenerals.first { it.id == 9 }
        assertEquals(500, af9.gold, "m9 gold≤1000 → unchanged")
        assertEquals(4000, af9.rice, "m9 rice UNTOUCHED (legacy rice bug)")

        // --- cascade 재야화: 전 장수 nation=0/officer_level=0/officer_city=0/troop=0/belong=0/permission=normal ---
        for (g in listOf(draft.general, af17, af9)) {
            assertEquals(0, g.nationId, "[g${g.id}] nation=0")
            assertEquals(0, g.officerLevel, "[g${g.id}] officer_level=0")
            assertEquals(0, g.officerCity, "[g${g.id}] officer_city=0")
            assertEquals(0, g.troop, "[g${g.id}] troop=0")
            assertEquals(0, metaInt(g.meta, "belong"), "[g${g.id}] belong=0")
            assertEquals("normal", g.meta["permission"], "[g${g.id}] permission=normal")
        }

        // --- 군주 makelimit=12 (php:109) ---
        assertEquals(12, metaInt(draft.general.meta, "makelimit"), "lord makelimit=12")

        // --- npcType<2 만 aux.max_belong=max(belong, prev) (func.php:1755-1762) ---
        @Suppress("UNCHECKED_CAST")
        val lordAux = draft.general.meta["aux"] as Map<String, Any?>
        assertEquals(5, (lordAux["max_belong"] as Number).toInt(), "lord(npc0) max_belong=max(belong5,0)=5")
        @Suppress("UNCHECKED_CAST")
        val aux17 = af17.meta["aux"] as Map<String, Any?>
        assertEquals(8, (aux17["max_belong"] as Number).toInt(), "m17(npc0) max_belong=max(belong8,0)=8")
        assertTrue(af9.meta["aux"] == null || (af9.meta["aux"] as Map<*, *>)["max_belong"] == null,
            "m9(npc3>=2) skips max_belong update")

        // --- 도시 공백지: nation=0, front=0 ---
        assertTrue(draft.cascadeCities.all { it.nationId == 0 }, "cascade-cities nation=0")
        assertTrue(draft.cascadeCities.all { it.frontState == 0 }, "cascade-cities front=0")

        // --- 국가 tombstone seam ---
        assertEquals(7, cmd.lastDeletedNationId, "nation 7 marked for deletion (engine tombstone seam)")
        assertNull(cmd.lastAlternative, "no alternative on a real disband")

        // --- 로그 byte (PHP che_해산.php:103-104 verbatim) ---
        assertEquals(
            listOf(
                "<C>●</>${MONTH}월:세력을 해산했습니다. <1>$date</>",
                // 군주 자기 PLAIN 멸망 로그(addActionPlainLog) — func.php:1772 PLAIN.
                "<C>●</><D><b>위</b></>가 <R>멸망</>했습니다.",
            ),
            context.logs(),
            "[해산] actor action-log byte-match (해산 + PLAIN 멸망)",
        )
        assertEquals(
            listOf("<C>●</>${MONTH}월:<Y>조조</>가 세력을 해산했습니다."),
            context.globalActionLogs(),
            "[해산] global action-log byte-match",
        )
        // 멤버 PLAIN 멸망 로그(각 장수 자기 로거).
        assertEquals(listOf("<C>●</><D><b>위</b></>가 <R>멸망</>했습니다."), context.plainLogsTo(17),
            "[해산] m17 PLAIN 멸망 byte-match")
        assertEquals(listOf("<C>●</><D><b>위</b></>가 <R>멸망</>했습니다."), context.plainLogsTo(9),
            "[해산] m9 PLAIN 멸망 byte-match")

        // --- created-set EMPTY (해산은 INSERT 없음) ---
        assertTrue(draft.createdNations.isEmpty() && draft.createdNationTurns.isEmpty(),
            "해산 inserts no nation/nation_turn (deletion only)")
    }
}
