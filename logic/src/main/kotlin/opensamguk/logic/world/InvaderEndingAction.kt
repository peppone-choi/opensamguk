package opensamguk.logic.world

import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

/**
 * A3 — `InvaderEnding` 월드 이벤트 leaf. 침략자(이민족) 이벤트의 종료/정리를 담당한다.
 *
 * PHP `sammo/Event/Action/InvaderEnding.php`(73줄)의 충실 포팅. RaiseInvader의 짝으로, 침략자
 * 이벤트가 켜진 상태(`isunited == 1`)에서 다음 종료 조건 중 하나가 충족되면 엔딩을 확정한다.
 *
 *  1. `isunited`를 읽어 `[0, 2]`에 속하면(침략자 이벤트 비활성) **No Invader** — no-op.
 *  2. 국가 수 `>= 2`이면(아직 다국가 난립) **On Event** — no-op.
 *  3. 공백지(`nation = 0`) 도시 수를 센다.
 *     - `cityCnt == 0`(이민족이 전멸 → 마지막 국가가 중원을 모두 차지)이면 종료. 마지막으로 남은
 *       국가명이 `ⓞ`(이민족 prefix, GeneralBuilder.php:51)로 시작하지 **않으면** 유저(한족) 승리.
 *     - `cityCnt == 전체 도시 수`(모든 도시가 공백지 = 이민족이 중원 전체를 장악)이면 종료, 유저 패배.
 *  4. 위 두 종료 조건 어디에도 해당 안 되면 **On Event** — no-op.
 *  5. 종료가 확정되면 승/패에 따라 글로벌 히스토리 로그 2줄을 푸시한다(byte-exact).
 *  6. PHP side-effect 순서 그대로: 로그 push → `setValue('isunited', 3)` → flush → `refreshLimit *= 100`
 *     → 자기 event row 삭제(`DELETE FROM event WHERE id = currentEventID`).
 *
 * **draw 없음.** 본문에 RNG 호출이 단 한 번도 없다(0-draw — A3 실행표 골든 N). 종료 조건 판정과
 * 결정적 effect/로그만 있다.
 *
 * 다른 월드 leaf(RandomizeCityTradeRateAction / B5 light Action)와 동일하게, tick/daemon이 풍부한
 * 컨텍스트([InvaderEndingContext])를 `env[InvaderEndingContext.ENV_KEY]` 아래로 스레드한다. 컨텍스트가
 * 없으면(아직 G1 daemon 배선 전) no-op — byte-match 핵심 로직은 본 leaf의 테스트로 검증한다. F2가
 * sealed base / [EventActionFactory] / EventStore row seed를 소유하므로, 본 leaf는 이름으로 자기
 * 자신만 등록한다(plan §append protocol).
 */
class InvaderEndingAction : EventAction {

    override fun run(ctx: EventActionContext) {
        val world = ctx.env[InvaderEndingContext.ENV_KEY] as? InvaderEndingContext ?: return

        // (1) isunited ∈ {0, 2} → No Invader. (InvaderEnding.php:20-23)
        val isunited = world.isunited()
        if (isunited == 0 || isunited == 2) return

        // (2) 국가 수 >= 2 → On Event. (php:25-28)
        if (world.nationCount() >= 2) return

        // (3) 종료 조건 판정. (php:34-47)
        var needStop = false
        var userWin = false
        val cityCnt = world.neutralCityCount()
        if (cityCnt == 0) {
            // 이민족 전멸 → 마지막 국가가 중원을 모두 차지.
            needStop = true
            val nationName = world.firstNationName()
            // ⓞ(이민족 prefix)로 시작하지 않으면 유저(한족) 승리. (php:39-42)
            if (nationName != null && !nationName.startsWith("ⓞ")) {
                userWin = true
            }
        } else if (cityCnt == world.totalCityCount()) {
            // 모든 도시가 공백지 = 이민족이 중원 전체 장악 → 유저 패배.
            needStop = true
            userWin = false
        }

        // (4) 종료 조건 미충족 → On Event. (php:49-51)
        if (!needStop) return

        // (5) 승/패 엔딩 로그 2줄(byte-exact). (php:53-62)
        if (userWin) {
            // 천통 엔딩
            world.pushGlobalHistoryLog("<L><b>【이벤트】</b></>이민족을 모두 소탕했습니다!")
            world.pushGlobalHistoryLog("<L><b>【이벤트】</b></>중원은 당분간 태평성대를 누릴 것입니다.")
        } else {
            // 이민족 엔딩
            world.pushGlobalHistoryLog("<L><b>【이벤트】</b></>중원은 이민족에 의해 혼란에 빠졌습니다.")
            world.pushGlobalHistoryLog("<L><b>【이벤트】</b></>백성은 언젠가 영웅이 나타나길 기다립니다.")
        }

        // (6) PHP side-effect 순서 그대로. (php:63-69)
        world.setIsunited(3)        // setValue('isunited', 3)
        world.flushLogs()           // logger->flush()
        world.multiplyRefreshLimit(100) // refreshLimit = refreshLimit * 100
        world.deleteOwnEvent(ctx.currentEventID) // DELETE FROM event WHERE id = currentEventID
    }

    companion object {
        /** PHP class name (EventStore seed가 부르는 `InvaderEnding` row token과 일치). */
        const val NAME: String = "InvaderEnding"

        /**
         * 본 leaf를 F2-owned [EventActionFactory]에 이름으로 등록(plan §append protocol).
         * seed가 인자 없이 이름만 두므로 builder는 (빈) 인자 리스트를 무시한다.
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { InvaderEndingAction() }
    }
}

/**
 * `InvaderEnding` leaf가 필요로 하는 풍부한 dispatch 컨텍스트(tick/daemon이 공급).
 * F2의 base [EventActionContext] env는 `year`/`month`/`currentEventID`만 운반하므로, 침략자 종료
 * 판정에 필요한 월드 read/write 시임을 별도 인터페이스로 둔다(RandomizeCityTradeRateContext 동일 패턴).
 *
 *  - [isunited] — game_env `isunited`(0=평시, 1=침략자 이벤트 진행, 2=천하통일, 3=엔딩 확정).
 *  - [nationCount] — `SELECT count(*) FROM nation`.
 *  - [neutralCityCount] — `SELECT count(*) FROM city WHERE nation = 0`(공백지 수).
 *  - [totalCityCount] — `count(CityConst::all())`(시나리오 전체 도시 수).
 *  - [firstNationName] — `SELECT name FROM nation LIMIT 1`(없으면 null).
 *  - [pushGlobalHistoryLog] — `ActionLogger::pushGlobalHistoryLog`.
 *  - [flushLogs] — `logger->flush()`(로그를 글로벌 히스토리에 확정).
 *  - [setIsunited] — game_env `isunited` 설정.
 *  - [multiplyRefreshLimit] — game_env `refreshLimit *= factor`.
 *  - [deleteOwnEvent] — `DELETE FROM event WHERE id = currentEventID`(1회용 event 자기 삭제).
 */
interface InvaderEndingContext : EventActionContext {
    fun isunited(): Int
    fun nationCount(): Int
    fun neutralCityCount(): Int
    fun totalCityCount(): Int
    fun firstNationName(): String?
    fun pushGlobalHistoryLog(msg: String)
    fun flushLogs()
    fun setIsunited(value: Int)
    fun multiplyRefreshLimit(factor: Int)
    fun deleteOwnEvent(eventID: Int)

    companion object {
        /** tick/daemon이 월드 뷰를 스레드하는 env 키(A-family ENV_WORLD 관례). */
        const val ENV_KEY = "invaderEndingWorld"
    }
}
