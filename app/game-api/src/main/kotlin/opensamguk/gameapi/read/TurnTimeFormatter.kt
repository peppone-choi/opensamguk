package opensamguk.gameapi.read

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

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
 * 동일한 substring 접근을 쓴다: `Instant`를 ISO-8601(UTC, `YYYY-MM-DDTHH:MM:SS...Z`)로 만든 뒤
 * 'T'를 공백으로 바꾸고 앞 19자를 취하면 PHP의 `substr(...,0,19)`와 같은 "YYYY-MM-DD HH:MM:SS"가 된다.
 *
 * 주: 엔진이 `turn_time`을 UTC `Instant`로 정규화(`WorldSnapshotLoader.toInstant()`)하므로
 * 여기서도 UTC 기준으로 슬라이스해 데몬 fail-log(HM)와 동일 좌표계를 유지한다. 시각 변환(타임존)이
 * 아니라 **문자열 위치 추출**이 패러티 타깃이라는 점이 핵심.
 */
object TurnTimeFormatter {

    /**
     * PHP `getTurnTime(TURNTIME_FULL)` 포팅 — "YYYY-MM-DD HH:MM:SS"(앞 19자). null이면 null
     * (PHP: turntime 키 부재 시 null 반환).
     */
    fun full(turnTime: Instant?): String? {
        if (turnTime == null) return null
        // Instant.toString()은 항상 ISO-8601(UTC). 초가 0이면 ".000" 등이 생략될 수 있으나
        // OffsetDateTime으로 고정 포맷(초까지)을 보장해 PHP substr(0,19)와 자릿수를 맞춘다.
        val iso = turnTime.atOffset(ZoneOffset.UTC).toString() // 예: 2026-06-03T10:30:00Z
        return slice0To19(iso)
    }

    /** OffsetDateTime 오버로드(GeneralReadEntity.turnTime 컬럼이 OffsetDateTime). */
    fun full(turnTime: OffsetDateTime?): String? = full(turnTime?.toInstant())

    /**
     * W0-2(P1-004) — PHP `cutTurn($date, $turnterm)`(func.php:946-961) 충실 포팅:
     *   baseDate = (date의 날짜 00:00) - 1일 + 1시간;
     *   diffMin  = intdiv(epochSec(date) - epochSec(base), 60); diffMin -= diffMin % turnterm;
     *   결과     = base + diffMin분.
     * 즉 "전날 01:00" 기준 turnterm 격자로 내림한 시각. PHP는 서버 TZ DateTime, 본 포팅은 UTC
     * `Instant` 좌표계 — 양변(turnTime/lastExecute)을 같은 함수로 잘라 **순서 비교**만 하므로
     * (GetReservedCommand.php:74) 좌표계 차이는 비교 결과에 영향이 없다.
     */
    fun cutTurn(date: Instant, turntermMin: Int): Instant {
        require(turntermMin > 0) { "turnterm must be positive" }
        val utc = date.atOffset(ZoneOffset.UTC)
        val base = utc.toLocalDate().minusDays(1).atStartOfDay().plusHours(1).toInstant(ZoneOffset.UTC)
        var diffMin = (date.epochSecond - base.epochSecond) / 60 // Kotlin Long 나눗셈 = PHP intdiv(0방향 절단)
        diffMin -= diffMin % turntermMin
        return base.plusSeconds(diffMin * 60)
    }

    /**
     * PHP 'Y-m-d H:i:s[.u]' 문자열(game_env.turntime 등)을 UTC `Instant`로 파싱. 형식 불일치/공백 →
     * null(방어적 — 비교 자체를 생략, 날조 금지). [cutTurn]과 같은 UTC 좌표계.
     */
    fun parseFull(s: String?): Instant? {
        if (s.isNullOrBlank() || s.length < 19) return null
        return runCatching {
            java.time.LocalDateTime.parse(s.substring(0, 19).replace(' ', 'T')).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

    /**
     * ISO-8601 문자열을 PHP `substr($turntime, 0, 19)`와 동형으로 변환:
     * 'T'를 공백으로 치환 후 앞 19자. 초 단위가 모자라면("...T10:30Z") 0초로 패딩해 19자를 보장.
     */
    private fun slice0To19(iso: String): String {
        val tIdx = iso.indexOf('T')
        if (tIdx < 0) return iso.take(19)
        val datePart = iso.substring(0, tIdx) // YYYY-MM-DD
        // 시각부에서 오프셋/Z/마이크로초 꼬리를 제거하고 HH:MM:SS만 추출.
        var timePart = iso.substring(tIdx + 1)
        // 'Z' 또는 '+'/'-' 오프셋, '.' 마이크로초 경계에서 자른다.
        val cut = timePart.indexOfFirst { it == 'Z' || it == '+' || it == '.' }
            .let { if (it >= 0) it else timePart.length }
        timePart = timePart.substring(0, cut)
        // HH:MM 만 있고 SS가 없으면 ":00"을 붙여 8자(HH:MM:SS)를 보장.
        if (timePart.length == 5) timePart += ":00"
        return "$datePart $timePart"
    }
}
