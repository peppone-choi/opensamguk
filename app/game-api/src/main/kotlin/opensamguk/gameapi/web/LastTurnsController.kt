package opensamguk.gameapi.web

import opensamguk.gameapi.read.HwihaLastTurnsReader
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 「지난 순」 조회(읽기 전용) — 본인 장수의 최근 순별 기록과 세력 요약. 인증·상태 규칙은 다른 휘하 조회와 같다
 * ([hwihaGuarded]): 401 · 403 · 200 + `status`(`READY`·`UNAVAILABLE`·`WRONG_RULE_PROFILE`), `no-store`.
 *
 * `limit` 은 1–36순으로 자른다(기본 12 — 명령 목록 12순의 거울).
 */
@RestController
class HwihaLastTurnsController(private val reader: HwihaLastTurnsReader) {
    @GetMapping("/api/hwiha/last-turns")
    fun lastTurns(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestParam(defaultValue = "12") limit: Int,
    ): ResponseEntity<Any> = hwihaGuarded(userId) { reader.lastTurns(generalId, it, limit) }
}
