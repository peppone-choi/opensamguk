package opensamguk.logic.council

import kotlinx.serialization.json.*

/** 계정/주체/세력/권한은 payload 밖 서버 신원 경계에서만 결정한다. */
sealed interface CouncilRequest {
    data class PostArticle(val room: String, val kind: String, val title: String, val contentHtml: String,
                           val operationId: Int?) : CouncilRequest
    data class PostComment(val articleId: Int, val text: String) : CouncilRequest
    data class MarkRead(val articleId: Int) : CouncilRequest
    data class GrantAccess(val targetGeneralId: Int, val expectedRevision: String?) : CouncilRequest
    data class RevokeAccess(val targetGeneralId: Int, val expectedRevision: String) : CouncilRequest
}

object CouncilRequestCodec {
    const val POST_ARTICLE = "POST_ARTICLE"
    const val POST_COMMENT = "POST_COMMENT"
    const val MARK_READ = "MARK_READ"
    const val GRANT_ACCESS = "GRANT_ACCESS"
    const val REVOKE_ACCESS = "REVOKE_ACCESS"

    fun parse(action: String, raw: String): CouncilRequest? = try {
        val row = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        when (action) {
            POST_ARTICLE -> {
                keys(row, setOf("room", "kind", "title", "contentHtml"), setOf("operationId"))
                val room = row.text("room").also { require(it in setOf("MEETING", "SECRET")) }
                val kind = row.text("kind").also { require(it in setOf("GENERAL", "OPERATION", "NOTICE")) }
                val title = row.text("title").trim().also { require(it.codePointCount(0, it.length) <= 250) }
                val html = row.text("contentHtml").trim().also { require(it.codePointCount(0, it.length) <= 65535) }
                require(title.isNotEmpty() || html.isNotEmpty())
                val op = row["operationId"]?.takeIf { it != JsonNull }?.let { row.integer("operationId") }
                require(kind == "OPERATION" || op == null)
                CouncilRequest.PostArticle(room, kind, title, html, op)
            }
            POST_COMMENT -> {
                keys(row, setOf("articleId", "text"))
                val text = row.text("text").trim().also { require(it.isNotEmpty() && it.codePointCount(0, it.length) <= 250) }
                CouncilRequest.PostComment(row.integer("articleId"), text)
            }
            MARK_READ -> { keys(row, setOf("articleId")); CouncilRequest.MarkRead(row.integer("articleId")) }
            GRANT_ACCESS -> {
                keys(row, setOf("targetGeneralId"), setOf("expectedRevision"))
                val revision = row["expectedRevision"]?.takeIf { it != JsonNull }?.let { row.text("expectedRevision") }
                revision?.let { require(it.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
                CouncilRequest.GrantAccess(row.integer("targetGeneralId"), revision)
            }
            REVOKE_ACCESS -> {
                keys(row, setOf("targetGeneralId", "expectedRevision"))
                val revision = row.text("expectedRevision").also { require(it.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) }
                CouncilRequest.RevokeAccess(row.integer("targetGeneralId"), revision)
            }
            else -> invalid()
        }
    } catch (_: RuntimeException) { null }

    fun encode(request: CouncilRequest): String = buildJsonObject {
        when (request) {
            is CouncilRequest.PostArticle -> {
                put("room", request.room); put("kind", request.kind); put("title", request.title)
                put("contentHtml", request.contentHtml); request.operationId?.let { put("operationId", it) }
            }
            is CouncilRequest.PostComment -> { put("articleId", request.articleId); put("text", request.text) }
            is CouncilRequest.MarkRead -> put("articleId", request.articleId)
            is CouncilRequest.GrantAccess -> {
                put("targetGeneralId", request.targetGeneralId)
                put("expectedRevision", request.expectedRevision?.let(::JsonPrimitive) ?: JsonNull)
            }
            is CouncilRequest.RevokeAccess -> {
                put("targetGeneralId", request.targetGeneralId); put("expectedRevision", request.expectedRevision)
            }
        }
    }.toString()

    private fun keys(row: JsonObject, required: Set<String>, optional: Set<String> = emptySet()) {
        require(row.keys.containsAll(required) && row.keys.all { it in required || it in optional })
    }
    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()
    private fun JsonObject.integer(key: String): Int =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("회의실 입력이 올바르지 않습니다.")
}
