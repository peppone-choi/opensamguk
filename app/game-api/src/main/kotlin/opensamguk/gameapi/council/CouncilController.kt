package opensamguk.gameapi.council

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/council")
class CouncilController(private val reader: CouncilReader) {
    @GetMapping
    fun page(@AuthenticationPrincipal userId: Long?, @RequestParam query: Map<String, String>): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(401)
            .body(CouncilFailure("AUTH_REQUIRED", "로그인이 필요합니다."))
        if (query.keys.any { it !in setOf("room", "kind", "cursor", "limit") }) return invalid()
        val room = runCatching { CouncilRoom.valueOf(query["room"] ?: "MEETING") }.getOrNull() ?: return invalid()
        val kind = query["kind"]?.takeIf { it.isNotEmpty() }?.let {
            runCatching { CouncilArticleKind.valueOf(it) }.getOrNull() ?: return invalid()
        }
        val limit = query["limit"]?.let { it.toIntOrNull() ?: return invalid() } ?: 50
        return try { ResponseEntity.ok(reader.page(userId, room, kind, query["cursor"], limit)) }
        catch (failure: CouncilReadFailure) {
            ResponseEntity.status(failure.status).body(CouncilFailure(failure.code, failure.explanation))
        }
    }

    private fun invalid(): ResponseEntity<Any> = ResponseEntity.badRequest()
        .body(CouncilFailure("INVALID_REQUEST", "회의실 조회 조건이 올바르지 않습니다."))
}
