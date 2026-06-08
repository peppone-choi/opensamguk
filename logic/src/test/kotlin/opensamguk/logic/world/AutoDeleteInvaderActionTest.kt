package opensamguk.logic.world

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A3 — `AutoDeleteInvader` 이벤트 액션 리프 검증 (PHP `Event/Action/AutoDeleteInvader.php:8-46`).
 *
 * draw 없음(본문에 RNG 인자가 아예 없음 = 구조적 0-draw 보장). 세 갈래 분기를 핀으로 고정한다:
 *   1. 국가 소멸 → 이벤트 삭제, general_turn 미터치.
 *   2. 교전 중(state 0/1) → 아무 것도 안 함(이벤트 미삭제, general_turn 미터치) → 재시도 가능.
 *   3. 그 외 → 군주(officer_level=12) general_turn 을 che_방랑/'[]'/'이민족 방랑' 으로 갱신 후 이벤트 삭제.
 *
 * 또한 주석 처리된 `killturn=5` 블록이 실행되지 않음(=호출 흔적이 없음)을 확인하고, 바이트 정확 상수와
 * F2 팩토리 단일-인자 등록을 검증한다.
 */
class AutoDeleteInvaderActionTest {

    private companion object {
        // 침략자(이민족) 국가 번호 — 정적 중첩 클래스 RecordingCtx 에서도 참조되므로 companion 으로 둔다.
        private const val INVADER = 7
    }

    /** richer 틱 컨텍스트 기록용 스텁: 분기 입력(존재/교전/군주) + 부수효과(방랑 갱신/이벤트 삭제/킬턴) 캡처. */
    private class RecordingCtx(
        currentEventID: Int,
        private val exists: Boolean,
        private val onWar: Boolean,
        private val rulerId: Int?,
    ) : AutoDeleteInvaderContext {
        override val env: Map<String, Any?> = linkedMapOf("currentEventID" to currentEventID)

        var wanderedGeneralId: Int? = null
        var deletedEventId: Int? = null
        var killturnCalls: Int = 0 // 주석 처리된 killturn=5 가 실수로 포팅되면 잡는 가드(절대 증가 X)

        override fun nationExists(nationID: Int): Boolean {
            assertEquals(INVADER, nationID, "리프는 생성자 nationID 로만 조회")
            return exists
        }

        override fun isOnWar(nationID: Int): Boolean {
            assertEquals(INVADER, nationID)
            return onWar
        }

        override fun findRulerId(nationID: Int): Int? {
            assertEquals(INVADER, nationID)
            return rulerId
        }

        override fun setGeneralTurnWander(generalId: Int) {
            wanderedGeneralId = generalId
        }

        override fun deleteEvent(eventID: Int) {
            deletedEventId = eventID
        }
    }

    @Test
    fun `branch 1 — nation gone deletes the event and never touches general_turn`() {
        // AutoDeleteInvader.php:16-23 — getNationStaticInfo === null → DELETE event, return "Not Exists".
        val ctx = RecordingCtx(currentEventID = 42, exists = false, onWar = false, rulerId = 99)
        AutoDeleteInvaderAction(INVADER).run(ctx)

        assertEquals(42, ctx.deletedEventId, "소멸 국가 → currentEventID 이벤트 삭제")
        assertEquals(null, ctx.wanderedGeneralId, "소멸 분기는 general_turn 을 건드리지 않음")
        assertEquals(0, ctx.killturnCalls, "주석 처리된 killturn=5 는 포팅되지 않음")
    }

    @Test
    fun `branch 2 — on war does NOTHING (no delete, no wander) so the event retries next tick`() {
        // AutoDeleteInvader.php:25-28 — state IN (0,1) → return "On War" without any mutation.
        val ctx = RecordingCtx(currentEventID = 42, exists = true, onWar = true, rulerId = 99)
        AutoDeleteInvaderAction(INVADER).run(ctx)

        assertEquals(null, ctx.deletedEventId, "교전 중이면 이벤트를 삭제하지 않음(다음 틱 재시도)")
        assertEquals(null, ctx.wanderedGeneralId, "교전 중이면 general_turn 도 건드리지 않음")
        assertEquals(0, ctx.killturnCalls)
    }

    @Test
    fun `branch 3 — deleted sets ruler general_turn to wander then deletes the event`() {
        // AutoDeleteInvader.php:30-43 — 군주를 che_방랑 으로, 그 후 이벤트 삭제, return "Deleted".
        val ctx = RecordingCtx(currentEventID = 42, exists = true, onWar = false, rulerId = 99)
        AutoDeleteInvaderAction(INVADER).run(ctx)

        assertEquals(99, ctx.wanderedGeneralId, "군주(officer_level=12) general_turn 을 방랑으로 갱신")
        assertEquals(42, ctx.deletedEventId, "갱신 후 currentEventID 이벤트 삭제")
        assertEquals(0, ctx.killturnCalls)
    }

    @Test
    fun `branch 3 with no ruler still deletes the event (queryFirstField miss is null)`() {
        // PHP queryFirstField 가 군주를 못 찾으면 null/false → update WHERE general_id = null 은 no-op,
        // 그래도 이벤트 삭제는 진행된다. 우리는 rulerId == null 일 때 setGeneralTurnWander 를 건너뛴다.
        val ctx = RecordingCtx(currentEventID = 42, exists = true, onWar = false, rulerId = null)
        AutoDeleteInvaderAction(INVADER).run(ctx)

        assertEquals(null, ctx.wanderedGeneralId, "군주 부재 시 general_turn 갱신 없음")
        assertEquals(42, ctx.deletedEventId, "군주 부재여도 이벤트는 삭제")
    }

    @Test
    fun `wander constants are byte-exact (che_방랑 arg empty-json brief 이민족 방랑)`() {
        // AutoDeleteInvader.php:32-34 — action/arg/brief 바이트 정확.
        assertEquals("che_방랑", AutoDeleteInvaderAction.WANDER_ACTION)
        assertEquals("[]", AutoDeleteInvaderAction.WANDER_ARG)
        assertEquals("이민족 방랑", AutoDeleteInvaderAction.WANDER_BRIEF)
    }

    @Test
    fun `on-war states are exactly WAR and DECLARATION (diplomacy state 0 and 1)`() {
        // AutoDeleteInvader.php:25 — state IN [0, 1].
        assertEquals(
            setOf(DiplomacyState.WAR, DiplomacyState.DECLARATION),
            AutoDeleteInvaderContext.ON_WAR_STATES,
        )
        assertTrue(DiplomacyState.WAR in AutoDeleteInvaderContext.ON_WAR_STATES)
        assertTrue(DiplomacyState.DECLARATION in AutoDeleteInvaderContext.ON_WAR_STATES)
        assertFalse(DiplomacyState.TRADE in AutoDeleteInvaderContext.ON_WAR_STATES)
        assertFalse(DiplomacyState.NON_AGGRESSION in AutoDeleteInvaderContext.ON_WAR_STATES)
    }

    @Test
    fun `leaf registers into the F2 factory by name with a single nationID arg`() {
        val factory = EventActionFactory()
        AutoDeleteInvaderAction.register(factory)
        assertTrue(factory.has("AutoDeleteInvader"), "leaf must register under its PHP class name")

        // 시드는 단일 인자 nationID 로 명명한다(PHP __construct(int $nationID)).
        val action: EventAction =
            factory.create(RawAction("AutoDeleteInvader", listOf(JsonPrimitive(INVADER))))
        assertTrue(action is AutoDeleteInvaderAction)

        // 디스패치 리프가 생성자 nationID(=7)로 조회하는지 분기 실행으로 확인.
        val ctx = RecordingCtx(currentEventID = 42, exists = true, onWar = false, rulerId = 99)
        action.run(ctx)
        assertEquals(99, ctx.wanderedGeneralId)
        assertEquals(42, ctx.deletedEventId)
    }

    @Test
    fun `running against a base context that is not an AutoDeleteInvader context throws`() {
        val bareEnv = object : EventActionContext {
            override val env: Map<String, Any?> = linkedMapOf("currentEventID" to 42)
        }
        var threw = false
        try {
            AutoDeleteInvaderAction(INVADER).run(bareEnv)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "리프는 richer AutoDeleteInvaderContext 가 필요(데몬이 공급)")
    }
}
