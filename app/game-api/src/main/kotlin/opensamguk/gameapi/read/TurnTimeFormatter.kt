package opensamguk.gameapi.read

import opensamguk.logic.tick.ServerClock
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * W3-F3 — PHP `General::getTurnTime(TURNTIME_FULL)`의 공유 포팅(ChiefCenter + GeneralList 소비).
 *
 * PHP 원천(`GeneralBase.php:57-77`): `turntime`은 DB에 `YYYY-MM-DD HH:MM:SS[.ffffff]` 형태의
 * 문자열로 저장되며, 각 포맷은 그 문자열에 대한 **순수 substr 연산**이다:
 *   - TURNTIME_FULL(0)    = substr($turntime, 0, 19)  → "YYYY-MM-DD HH:MM:SS"(앞 19자)
 *   - TURNTIME_FULL_MS(-1)= 원문 그대로(마이크로초 포함)
 *   - TURNTIME_HMS(1)     = substr($turntime, 11, 8)  → "HH:MM:SS"
 *   - TURNTIME_HM(2)      = substr($turntime, 11, 5)  → "HH:MM"
 *
 * opensamguk의 `general.turn_time`은 `timestamptz`라 PHP의 datetime-문자열과 직접 1:1은 아니다.
 * 패러티 정합을 위해 엔진의 기존 선례(`AiTurnAdapter.turnTimeHm` = `Instant.toString()`을 substr)와
 * 동일한 substring 접근을 쓴다. 다만 PHP `DateTime`의 wall-clock 기준은 서버 시간대이므로
 * opensamguk의 고정 서버 시간대(`Asia/Seoul`)에서 "YYYY-MM-DD HH:MM:SS"를 만든다.
 *
 * 주: 엔진은 `turn_time`을 절대시각 `Instant`로 보관하고, 표시/슬라이스는 [ServerClock.SERVER_ZONE]에서
 * 수행한다. 시각 변환이 아니라 **서버 wall-clock 문자열 위치 추출**이 패러티 타깃이라는 점이 핵심.
 */
object TurnTimeFormatter {
    private val FULL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * PHP `getTurnTime(TURNTIME_FULL)` 포팅 — "YYYY-MM-DD HH:MM:SS"(앞 19자). null이면 null
     * (PHP: turntime 키 부재 시 null 반환).
     */
    fun full(turnTime: Instant?): String? {
        if (turnTime == null) return null
        return turnTime.atZone(ServerClock.SERVER_ZONE).format(FULL_FORMATTER)
    }

    /** OffsetDateTime 오버로드(GeneralReadEntity.turnTime 컬럼이 OffsetDateTime). */
    fun full(turnTime: OffsetDateTime?): String? = full(turnTime?.toInstant())

    /**
     * W0-2(P1-004) — PHP `cutTurn($date, $turnterm)`(func.php:946-961) 충실 포팅:
     *   baseDate = (date의 날짜 00:00) - 1일 + 1시간;
     *   diffMin  = intdiv(epochSec(date) - epochSec(base), 60); diffMin -= diffMin % turnterm;
     *   결과     = base + diffMin분.
     * 즉 "전날 01:00" 기준 turnterm 격자로 내림한 시각. PHP는 서버 TZ DateTime이므로
     * [ServerClock.SERVER_ZONE]의 local date를 기준으로 anchor를 잡는다.
     */
    fun cutTurn(date: Instant, turntermMin: Int): Instant {
        require(turntermMin > 0) { "turnterm must be positive" }
        val local = date.atZone(ServerClock.SERVER_ZONE)
        val base = local.toLocalDate().minusDays(1).atStartOfDay(ServerClock.SERVER_ZONE).plusHours(1).toInstant()
        var diffMin = (date.epochSecond - base.epochSecond) / 60 // Kotlin Long 나눗셈 = PHP intdiv(0방향 절단)
        diffMin -= diffMin % turntermMin
        return base.plusSeconds(diffMin * 60)
    }

    /**
     * PHP 'Y-m-d H:i:s[.u]' 문자열(game_env.turntime 등)을 서버 시간대 `Instant`로 파싱. 형식 불일치/공백 →
     * null(방어적 — 비교 자체를 생략, 날조 금지). [cutTurn]과 같은 좌표계.
     */
    fun parseFull(s: String?): Instant? {
        if (s.isNullOrBlank() || s.length < 19) return null
        return runCatching {
            LocalDateTime.parse(s.substring(0, 19).replace(' ', 'T'))
                .atZone(ServerClock.SERVER_ZONE)
                .toInstant()
        }.getOrNull()
    }
}
