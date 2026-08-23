package opensamguk.logic.actions.nation

import opensamguk.logic.domain.General
import opensamguk.logic.event.StaticEventHandler
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * 외교 7종(제의 3 + 수락 3 + 등용수락) 공통 인프라 (A2 공통 인프라 — FOUNDATION).
 *
 * 6개 외교 명령 에이전트가 **공유하는 단일 진입점**이다. 메시지 발송 effect 자체는 이미
 * [GeneralActionResolveContext.sendMessage] → [opensamguk.engine.turn.ReservedTurnHandler.routeMessage]
 * → `ChangeRecorder.recordMessageInsert` → `JdbcFlushExecutor.messageCreateMany`로 byte-faithful하게
 * 배선되어 있으므로(메일함 = 9000+destNationID = 국가 메일함), 이 객체는 그 위에서 명령마다 흩어지면
 * 발산하기 쉬운 **두 가지 공통 계산**만 정본화한다:
 *
 *  1. **validUntil 공식** — PHP `che_*제의.php`의 `$validMinutes = max(30, $env['turnterm']*3)` →
 *     `$validUntil = $date + PT{validMinutes}M`. (che_불가침제의.php:202-204 / che_종전제의.php:149-151 /
 *     che_불가침파기제의.php:151-153 — 세 명령이 byte-동일.) 현재 명령 본체가 `max(30, 60*3)`을
 *     하드코딩하고 있어 turnterm이 prod 기본값(60)이 아닌 서버에서 발산한다 — 이 헬퍼가 turnterm을
 *     받아 단일 공식으로 묶는다.
 *
 *  2. **StaticEventHandler 외교 훅** — PHP `run()` 말미의
 *     `StaticEventHandler::handleEvent($general, $destGeneral, $this::class, $env, $arg)` 호출
 *     (che_불가침제의.php:222 등). `setResultTurn` 뒤 · `applyDB` 앞 순서. scenario 1010에서 핸들러
 *     맵이 비어 있어 no-op이지만(게이트 동일), **호출 지점**은 외교 7종 공통 메커니즘이므로 여기서
 *     단일화한다. 역사 PHP `$this::class` 비교는 동결 회귀 참고일 뿐 현재 제품 정본이 아니다
 *     (ADR-LITE-042); Kotlin에서는 eventType으로 명령 key를 넘긴다.
 *
 * 주의(왜 effect를 여기서 새로 만들지 않는가): message:send effect kind는 이미 존재하고 flush까지
 * 배선·테스트(MessageFlushIT)되어 있다. 외교 명령은 그저 [GeneralActionResolveContext.sendMessage]에
 * [opensamguk.logic.message.Message](msgType=DIPLOMACY, option.action=...)를 buffer하면 된다. 이 객체는
 * 그 메시지의 **validUntil 문자열**과 **이벤트 훅 호출**만 표준화한다 — 발송 경로 자체를 우회하지 않는다.
 */
object DiplomacySeam {

    /** PHP `Y-m-d H:i:s` 포맷(메시지 date/validUntil 직렬화 — wall-clock, quarantine 동일). */
    val YMDHIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 외교 서신 유효시간(분) — PHP `max(30, $env['turnterm'] * 3)` (che_*제의.php 공통).
     *
     * @param turnterm 게임 env의 `turnterm`(턴 간격 분). prod 기본값 60.
     * @return `max(30, turnterm*3)` 분.
     */
    fun validMinutes(turnterm: Int): Int = max(30, turnterm * 3)

    /**
     * 외교 서신 `validUntil` 문자열 — PHP `$validUntil = new DateTime($date); $validUntil->add(PT{m}M)`.
     *
     * 6개 외교 제의 명령이 호출하는 단일 공식. [sendDate]는 PHP `$general->getTurnTime(TURNTIME_HM)`
     * 기준 발송 시각(wall-clock — quarantine 대상). 반환값을
     * [opensamguk.logic.message.Message] 생성자의 `validUntil`로 그대로 사용한다.
     *
     * @param sendDate 발송 기준 시각(`Y-m-d H:i:s`로 직렬화할 [LocalDateTime]).
     * @param turnterm 게임 env `turnterm`(분).
     * @return `sendDate + max(30, turnterm*3)분`을 `Y-m-d H:i:s`로 직렬화한 문자열.
     */
    fun validUntil(sendDate: LocalDateTime, turnterm: Int): String =
        sendDate.plusMinutes(validMinutes(turnterm).toLong()).format(YMDHIS)

    /**
     * 외교 명령 resolve 직후의 StaticEventHandler 훅 호출 — PHP
     * `StaticEventHandler::handleEvent($general, $destGeneral, $this::class, $env, $arg)`.
     *
     * 외교 7종 공통. resolve() 본체가 [GeneralActionResolveContext.sendMessage] + 로그 buffer를 끝낸 뒤
     * (PHP의 `setResultTurn` 뒤 · `applyDB` 앞 순서에 대응) 이 메서드를 호출한다. scenario 1010에서는
     * 핸들러 맵이 비어 있어 no-op(게이트 동일) — 호출 지점만 공통으로 정본화한다.
     *
     * @param general 행동 장수(PHP `$this->generalObj`).
     * @param destGeneral 상대 장수(PHP `$this->destGeneralObj`) — 제의에는 없을 수 있어 nullable.
     * @param commandKey 명령 key(PHP `$this::class` 대응 — 외교 명령의 액션 코드).
     * @param env 게임 env 맵(PHP `$this->env`).
     * @param args 파싱된 인자 맵(PHP `$this->arg`).
     */
    fun fireDiplomacyEvent(
        general: General,
        destGeneral: General?,
        commandKey: String,
        env: Map<String, Any?>,
        args: Map<String, Any?>,
    ) {
        StaticEventHandler.handleEvent(general, destGeneral, commandKey, env, args)
    }
}
