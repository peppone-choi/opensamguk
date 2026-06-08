package opensamguk.logic.world

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

/**
 * A3 — `AutoDeleteInvader` 이벤트 액션 리프 (PHP `Event/Action/AutoDeleteInvader.php:8-46` 충실 포팅).
 *
 * 침략자(이민족) 국가를 만료 조건에 따라 자동 정리하는 1회용 월드 이벤트. 생성자 인자 `nationID`는 정리 대상
 * 침략자 국가 번호로, F2 `RawAction.args[0]`에서 온다(PHP `__construct(int $nationID)`).
 *
 * PHP `run(array $env)`는 **draw가 전혀 없다**(RNG 미사용). 세 갈래로 분기한다:
 *   1. **국가 소멸** — `getNationStaticInfo($nationID) === null` (대상 국가가 이미 사라짐):
 *      `DELETE FROM event WHERE id = currentEventID` 후 `[__CLASS__, "Not Exists"]` 반환.
 *   2. **교전 중** — `SELECT count(*) FROM diplomacy WHERE me = nationID AND state IN (0,1)` 이 양수
 *      (전쟁/선전포고 상태가 하나라도 있으면): **아무것도 하지 않고** `[__CLASS__, "On War"]` 반환.
 *      이벤트는 삭제되지 않고 남아 다음 틱에 재시도된다(파일 boundary상 핵심 분기 — 절대 삭제하지 않는다).
 *   3. **그 외(삭제)** — 군주(`officer_level = 12`)의 `general_turn`을 `action='che_방랑'`, `arg='[]'`,
 *      `brief='이민족 방랑'` 으로 갱신 → `DELETE FROM event WHERE id = currentEventID` →
 *      `[__CLASS__, 'Deleted']` 반환.
 *
 * 반환 튜플(`[__CLASS__, ...]`)은 디스패처가 폐기하므로 로그/효과로 노출되지 않는다(EventAction `run`은 `Unit`).
 * 또한 PHP 본문의 `killturn=5` 두 블록은 **주석 처리되어 실행되지 않으므로** 포팅하지 않는다(주석으로만 보존).
 *
 * 월드 뮤테이션 seam(국가 존재/교전 조회 + 군주 조회 + general_turn 갱신 + 이벤트 tombstone)은 데몬/G1이
 * 공급하는 [AutoDeleteInvaderContext]가 담당한다 — F2의 베이스 [EventActionContext]/[EventActionFactory]는
 * 그대로 두고, 이 리프는 자신을 이름으로 등록만 한다(plan §append protocol).
 */
class AutoDeleteInvaderAction(private val nationID: Int) : EventAction {

    override fun run(ctx: EventActionContext) {
        val world = ctx as? AutoDeleteInvaderContext
            ?: error(
                "AutoDeleteInvader needs the richer AutoDeleteInvaderContext " +
                    "(nation existence/war lookup + ruler lookup + general_turn sink + event tombstone) " +
                    "— the daemon must supply it at dispatch",
            )

        // (1) AutoDeleteInvader.php:16-23 — 대상 국가가 이미 소멸 → 이벤트만 삭제하고 종료.
        //     (주석 처리된 `general.killturn=5` (nation=0 AND npc=9) 블록은 실행되지 않으므로 미포팅.)
        if (!world.nationExists(nationID)) {
            world.deleteEvent(ctx.currentEventID)
            return // [__CLASS__, "Not Exists"]
        }

        // (2) AutoDeleteInvader.php:25-28 — 교전(state IN (0,1)) 중이면 정리 보류. 이벤트는 남겨 재시도.
        if (world.isOnWar(nationID)) {
            return // [__CLASS__, "On War"]
        }

        // (3) AutoDeleteInvader.php:30-43 — 군주(officer_level=12)를 방랑(che_방랑)으로 전환 후 이벤트 삭제.
        //     (주석 처리된 `general.killturn=5` (nation=nationID) 블록은 실행되지 않으므로 미포팅.)
        val rulerID = world.findRulerId(nationID)
        if (rulerID != null) {
            world.setGeneralTurnWander(rulerID)
        }
        world.deleteEvent(ctx.currentEventID)
        // [__CLASS__, 'Deleted']
    }

    companion object {
        /** PHP 클래스명(EventStore 시드의 액션 토큰과 일치). */
        const val NAME: String = "AutoDeleteInvader"

        /** PHP `général_turn` 갱신 시 쓰는 방랑 액션 코드(che_방랑.php). */
        const val WANDER_ACTION: String = "che_방랑"

        /** PHP `arg` = `'[]'` (빈 JSON 배열 문자열, AutoDeleteInvader.php:33). */
        const val WANDER_ARG: String = "[]"

        /** PHP `brief` = `'이민족 방랑'` (바이트 정확, AutoDeleteInvader.php:34). */
        const val WANDER_BRIEF: String = "이민족 방랑"

        /**
         * F2 [EventActionFactory]에 이름으로 등록(F2 소유 코드에 대한 유일한 per-family 터치, plan §append).
         * 시드는 단일 인자 `nationID`로 액션을 명명하므로 빌더가 `args[0]`을 정수로 읽어 생성한다.
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                val nationID = (args[0] as JsonPrimitive).content.toInt()
                AutoDeleteInvaderAction(nationID)
            }
    }
}

/**
 * AutoDeleteInvader 리프가 필요로 하는 richer 디스패치 컨텍스트(데몬이 공급; F2 베이스 env는 `currentEventID`를
 * 담는다, plan §append protocol). 각 메서드는 PHP `$db->...` 호출 하나의 직역이다.
 *
 *  - [nationExists] — `getNationStaticInfo(nationID) !== null` (AutoDeleteInvader.php:16). 국가 소멸 여부.
 *  - [isOnWar] — `SELECT count(*) FROM diplomacy WHERE me = nationID AND state IN (0,1)` > 0
 *    (AutoDeleteInvader.php:25). state 0/1 = [DiplomacyState.WAR]/[DiplomacyState.DECLARATION].
 *  - [findRulerId] — `SELECT no FROM general WHERE nation = nationID AND officer_level = 12`
 *    (AutoDeleteInvader.php:30). 군주가 없으면 null(PHP `queryFirstField` 미스 → null/false).
 *  - [setGeneralTurnWander] — `UPDATE general_turn SET action='che_방랑', arg='[]', brief='이민족 방랑'
 *    WHERE general_id = rulerID` (AutoDeleteInvader.php:31-35).
 *  - [deleteEvent] — `DELETE FROM event WHERE id = currentEventID` (AutoDeleteInvader.php:21,41).
 */
interface AutoDeleteInvaderContext : EventActionContext {
    /** `getNationStaticInfo(nationID) !== null` — 대상 침략자 국가가 아직 존재하는가. */
    fun nationExists(nationID: Int): Boolean

    /** `diplomacy.me = nationID AND state IN (WAR, DECLARATION)` 가 하나라도 있으면 교전 중. */
    fun isOnWar(nationID: Int): Boolean

    /** 군주(`officer_level = 12`)의 general no; 없으면 null. */
    fun findRulerId(nationID: Int): Int?

    /** 해당 장수의 general_turn 을 방랑(che_방랑)으로 갱신. */
    fun setGeneralTurnWander(generalId: Int)

    /** currentEventID 이벤트 row tombstone(1회용 자기 삭제). */
    fun deleteEvent(eventID: Int)

    companion object {
        /** 교전 판정에 쓰는 diplomacy state 코드(AutoDeleteInvader.php:25 `[0, 1]`). */
        val ON_WAR_STATES: Set<Int> = setOf(DiplomacyState.WAR, DiplomacyState.DECLARATION)
    }
}
