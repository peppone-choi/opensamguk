package opensamguk.gameapi.council

import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import kotlinx.serialization.json.*
import opensamguk.logic.council.CouncilRequestCodec
import opensamguk.gameapi.reserve.AdmissionDenied
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/council")
class CouncilController(private val reader: CouncilReader, private val submission: CouncilSubmission? = null) {
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

    @PostMapping("/articles")
    fun postArticle(@AuthenticationPrincipal userId: Long?, @RequestBody raw: String) =
        submit(userId, CouncilRequestCodec.POST_ARTICLE, raw)

    @PostMapping("/articles/{articleId}/comments")
    fun postComment(@AuthenticationPrincipal userId: Long?, @PathVariable articleId: Int,
                    @RequestBody raw: String): ResponseEntity<Any> =
        withTarget(userId, CouncilRequestCodec.POST_COMMENT, "articleId", articleId, raw, setOf("text"))

    @PostMapping("/articles/{articleId}/read")
    fun markRead(@AuthenticationPrincipal userId: Long?, @PathVariable articleId: Int,
                 @RequestBody(required = false) raw: String?): ResponseEntity<Any> =
        withTarget(userId, CouncilRequestCodec.MARK_READ, "articleId", articleId, raw ?: "{}", emptySet())

    @PutMapping("/participants/{targetGeneralId}")
    fun grantAccess(@AuthenticationPrincipal userId: Long?, @PathVariable targetGeneralId: Int,
                    @RequestBody(required = false) raw: String?): ResponseEntity<Any> =
        withTarget(userId, CouncilRequestCodec.GRANT_ACCESS, "targetGeneralId", targetGeneralId,
            raw ?: "{}", setOf("expectedRevision"))

    @DeleteMapping("/participants/{targetGeneralId}")
    fun revokeAccess(@AuthenticationPrincipal userId: Long?, @PathVariable targetGeneralId: Int,
                     @RequestBody raw: String): ResponseEntity<Any> =
        withTarget(userId, CouncilRequestCodec.REVOKE_ACCESS, "targetGeneralId", targetGeneralId,
            raw, setOf("expectedRevision"))

    private fun withTarget(userId: Long?, action: String, targetKey: String, target: Int,
                           raw: String, allowedKeys: Set<String>): ResponseEntity<Any> {
        if (target <= 0) return invalid()
        val fields = try { Json.parseToJsonElement(raw) as? JsonObject }
            catch (_: RuntimeException) { null } ?: return invalid()
        if (fields.keys.any { it !in allowedKeys }) return invalid()
        val payload = JsonObject(fields + (targetKey to JsonPrimitive(target))).toString()
        return submit(userId, action, payload)
    }

    private fun submit(userId: Long?, action: String, raw: String): ResponseEntity<Any> {
        if (userId == null || userId <= 0) return ResponseEntity.status(401)
            .body(CouncilFailure("AUTH_REQUIRED", "로그인이 필요합니다."))
        val writer = submission ?: return ResponseEntity.status(503)
            .body(CouncilFailure("STATE_UNAVAILABLE", "회의실 접수 경로를 확인할 수 없습니다."))
        return try { ResponseEntity.status(202).body(writer.submit(userId, action, raw)) }
        catch (failure: CouncilReadFailure) {
            ResponseEntity.status(failure.status).body(CouncilFailure(failure.code, failure.explanation))
        } catch (failure: AdmissionDenied) {
            val status = when (failure.code) {
                "STATE_UNAVAILABLE", "POLICY_UNAVAILABLE" -> 503
                "INVALID_REQUEST" -> 400
                "REVISION_CONFLICT" -> 409
                else -> 403
            }
            ResponseEntity.status(status).body(CouncilFailure(failure.code, failure.message))
        }
    }

    private fun invalid(): ResponseEntity<Any> = ResponseEntity.badRequest()
        .body(CouncilFailure("INVALID_REQUEST", "회의실 조회 조건이 올바르지 않습니다."))
}
