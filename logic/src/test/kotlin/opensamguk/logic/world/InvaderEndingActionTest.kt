package opensamguk.logic.world

import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A3 — `InvaderEnding` 월드 이벤트 leaf 테스트 (PHP `Event/Action/InvaderEnding.php`).
 *
 * 침략자(이민족) 이벤트 종료/정리. **draw 없음**(0-draw, A3 실행표 골든 N) — RNG 호출이 단 한 번도
 * 없으며, 종료 조건 판정과 결정적 effect/로그(byte-exact)만 검증한다.
 *
 * 검증 매트릭스:
 *  - early no-op: isunited ∈ {0, 2} (php:20-23), 국가 수 >= 2 (php:25-28), 종료 조건 미충족 (php:49-51).
 *  - 유저 승리(천통 엔딩): 공백지 0 + 마지막 국가명이 ⓞ가 아님 (php:37-42, 53-56).
 *  - 이민족 승리(이민족 엔딩): ① 공백지 0인데 마지막 국가명이 ⓞ로 시작(userWin 유지 false),
 *    ② 모든 도시가 공백지(cityCnt == 전체) (php:44-47, 58-61).
 *  - PHP side-effect 순서: 로그 push → isunited=3 → flush → refreshLimit *= 100 → 자기 event 삭제.
 */
class InvaderEndingActionTest {

    /**
     * 기록형 fake 월드. 모든 read 입력은 생성자로 주입하고, 모든 mutation/log push는 순서 보존하여
     * 기록한다. **RNG 접근자가 없다** — 본 leaf가 0-draw임을 구조적으로 보증한다.
     */
    private class FakeWorld(
        private val isunited: Int,
        private val nationCount: Int,
        private val neutralCityCount: Int,
        private val totalCityCount: Int,
        private val firstNationName: String?,
        override val env: Map<String, Any?>,
    ) : InvaderEndingContext {
        // 관찰 가능한 side-effect를 발생 순서대로 한 리스트에 적재(순서 검증용).
        val sideEffects = mutableListOf<String>()
        val historyLogs = mutableListOf<String>()
        var isunitedSet: Int? = null
        var refreshLimitFactor: Int? = null
        var deletedEventId: Int? = null

        override fun isunited(): Int = isunited
        override fun nationCount(): Int = nationCount
        override fun neutralCityCount(): Int = neutralCityCount
        override fun totalCityCount(): Int = totalCityCount
        override fun firstNationName(): String? = firstNationName

        override fun pushGlobalHistoryLog(msg: String) {
            historyLogs.add(msg)
            sideEffects.add("log:$msg")
        }
        override fun flushLogs() { sideEffects.add("flush") }
        override fun setIsunited(value: Int) { isunitedSet = value; sideEffects.add("isunited=$value") }
        override fun multiplyRefreshLimit(factor: Int) { refreshLimitFactor = factor; sideEffects.add("refreshLimit*=$factor") }
        override fun deleteOwnEvent(eventID: Int) { deletedEventId = eventID; sideEffects.add("delete:$eventID") }
    }

    private fun ctx(world: InvaderEndingContext?, currentEventID: Int = 7): EventActionContext {
        val env = mutableMapOf<String, Any?>("currentEventID" to currentEventID, "year" to 200, "month" to 1)
        if (world != null) env[InvaderEndingContext.ENV_KEY] = world
        return object : EventActionContext { override val env = env }
    }

    private fun world(
        isunited: Int = 1,
        nationCount: Int = 1,
        neutralCityCount: Int = 5,
        totalCityCount: Int = 10,
        firstNationName: String? = "촉",
    ): FakeWorld = FakeWorld(isunited, nationCount, neutralCityCount, totalCityCount, firstNationName, emptyMap())

    // ── no-op 경로 ────────────────────────────────────────────────────────────
    @Test
    fun `world 컨텍스트가 없으면 no-op`() {
        // env에 InvaderEndingContext가 없으면(아직 daemon 배선 전) 조용히 no-op.
        InvaderEndingAction().run(ctx(null))
    }

    @Test
    fun `isunited가 0이면 No Invader no-op`() {
        // php:20-23 — in_array(isunited, [0,2]) → return [__CLASS__, "No Invader"]
        val w = world(isunited = 0)
        InvaderEndingAction().run(ctx(w))
        assertTrue(w.sideEffects.isEmpty(), "0=평시면 아무 effect 없음")
    }

    @Test
    fun `isunited가 2이면(천하통일) No Invader no-op`() {
        // php:21 — 2도 [0,2]에 포함.
        val w = world(isunited = 2)
        InvaderEndingAction().run(ctx(w))
        assertTrue(w.sideEffects.isEmpty())
    }

    @Test
    fun `국가 수가 2 이상이면 On Event no-op`() {
        // php:25-28 — nationCnt >= 2 → return [__CLASS__, "On Event"]
        val w = world(isunited = 1, nationCount = 2)
        InvaderEndingAction().run(ctx(w))
        assertTrue(w.sideEffects.isEmpty())
    }

    @Test
    fun `종료 조건 미충족(공백지가 0도 전체도 아님)이면 On Event no-op`() {
        // php:49-51 — needStop=false → return [__CLASS__, "On Event"]
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 5, totalCityCount = 10)
        InvaderEndingAction().run(ctx(w))
        assertTrue(w.sideEffects.isEmpty())
    }

    // ── 유저 승리(천통 엔딩) ──────────────────────────────────────────────────
    @Test
    fun `공백지 0 + 마지막 국가명이 ⓞ가 아니면 유저 승리(천통 엔딩) 로그`() {
        // php:37-42 — cityCnt==0 && !str_starts_with(name, 'ⓞ') → userWin=true (php:53-56 로그)
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 0, firstNationName = "촉")
        InvaderEndingAction().run(ctx(w))
        assertEquals(
            listOf(
                "<L><b>【이벤트】</b></>이민족을 모두 소탕했습니다!",
                "<L><b>【이벤트】</b></>중원은 당분간 태평성대를 누릴 것입니다.",
            ),
            w.historyLogs,
        )
    }

    // ── 이민족 승리(이민족 엔딩) ──────────────────────────────────────────────
    @Test
    fun `공백지 0이지만 마지막 국가명이 ⓞ로 시작하면 유저 승리 아님(이민족 엔딩)`() {
        // php:40 — str_starts_with(name, 'ⓞ')면 userWin 유지 false → 이민족 엔딩(php:58-61)
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 0, firstNationName = "ⓞ강족")
        InvaderEndingAction().run(ctx(w))
        assertEquals(
            listOf(
                "<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.",
                "<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.",
            ),
            w.historyLogs,
        )
    }

    @Test
    fun `모든 도시가 공백지(cityCnt == 전체)면 이민족 승리(이민족 엔딩) 로그`() {
        // php:44-47 — cityCnt == count(CityConst::all()) → needStop=true, userWin=false (php:58-61)
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 10, totalCityCount = 10)
        InvaderEndingAction().run(ctx(w))
        assertEquals(
            listOf(
                "<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.",
                "<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.",
            ),
            w.historyLogs,
        )
    }

    @Test
    fun `공백지 0인데 마지막 국가명이 null이면 이민족 엔딩(userWin 유지 false)`() {
        // php:39 — name이 없으면(LIMIT 1이 빈 결과) str_starts_with 분기 진입 못함 → userWin false.
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 0, firstNationName = null)
        InvaderEndingAction().run(ctx(w))
        assertEquals(
            listOf(
                "<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.",
                "<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.",
            ),
            w.historyLogs,
        )
    }

    // ── side-effect 순서 + 결정적 정리(0-draw) ────────────────────────────────
    @Test
    fun `종료 확정 시 PHP side-effect 순서 그대로 적용`() {
        // php:53-69 순서: 로그 2줄 push → isunited=3 → flush → refreshLimit*=100 → 자기 event 삭제.
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 0, firstNationName = "촉")
        InvaderEndingAction().run(ctx(w, currentEventID = 42))
        assertEquals(
            listOf(
                "log:<L><b>【이벤트】</b></>이민족을 모두 소탕했습니다!",
                "log:<L><b>【이벤트】</b></>중원은 당분간 태평성대를 누릴 것입니다.",
                "isunited=3",
                "flush",
                "refreshLimit*=100",
                "delete:42",
            ),
            w.sideEffects,
        )
        assertEquals(3, w.isunitedSet)
        assertEquals(100, w.refreshLimitFactor)
        assertEquals(42, w.deletedEventId)
    }

    @Test
    fun `0-draw — no-op 경로에서는 어떤 mutation도 없음`() {
        // 종료 미확정 경로는 RNG도, mutation도 일절 없다(0-draw + 무부작용 보증).
        val w = world(isunited = 1, nationCount = 1, neutralCityCount = 3, totalCityCount = 10)
        InvaderEndingAction().run(ctx(w))
        assertTrue(w.sideEffects.isEmpty())
        assertEquals(null, w.isunitedSet)
        assertEquals(null, w.refreshLimitFactor)
        assertEquals(null, w.deletedEventId)
    }

    // ── factory 등록(per-family 단일 touch) ───────────────────────────────────
    @Test
    fun `leaf는 PHP class name으로 factory에 등록된다`() {
        val factory = EventActionFactory().also { InvaderEndingAction.register(it) }
        assertTrue(factory.has("InvaderEnding"))
        val action = factory.create(RawAction("InvaderEnding", emptyList()))
        assertTrue(action is InvaderEndingAction)
    }
}
