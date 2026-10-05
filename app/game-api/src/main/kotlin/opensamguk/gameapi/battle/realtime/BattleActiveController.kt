package opensamguk.gameapi.battle.realtime

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class BattleActiveController(private val query: BattleActiveQuery) {
    @GetMapping("/api/battles/active")
    fun active(@AuthenticationPrincipal accountId: Long?,
               @RequestParam(required = false) generalId: String?): ResponseEntity<Any> {
        if (accountId == null || accountId <= 0 || accountId > Int.MAX_VALUE)
            return error(401, "AUTH_REQUIRED", "로그인이 필요합니다.")
        val actorId = generalId?.toIntOrNull()?.takeIf { it > 0 }
            ?: return error(400, "INVALID_GENERAL_ID", "장수 번호를 확인해 주세요.")
        return try {
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body<Any>(query.read(accountId, actorId))
        } catch (_: BattleActiveForbidden) {
            error(403, "FORBIDDEN", "본인 장수로만 조회할 수 있습니다.")
        } catch (_: BattleActiveUnavailable) {
            error(503, "SOURCE_UNAVAILABLE", "전투 목록을 불러올 수 없습니다.")
        }
    }

    private fun error(status: Int, code: String, message: String): ResponseEntity<Any> =
        ResponseEntity.status(status).cacheControl(CacheControl.noStore())
            .body<Any>(BattleActiveError(BattleActiveErrorDetail(code, message)))
}
