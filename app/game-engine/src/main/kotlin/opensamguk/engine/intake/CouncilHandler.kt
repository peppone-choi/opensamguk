package opensamguk.engine.intake

import opensamguk.common.wire.CouncilInput
import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.read.BoardPostRepository
import opensamguk.logic.content.PersistedMetaJson
import opensamguk.logic.council.*
import opensamguk.logic.input.RuleProfile
import java.util.Locale

/** Read current world and affiliation each time; JWT or admission roles do not authorize execution. */
fun interface CouncilExecutionAuthoritySource {
    fun read(nationId: Int): CouncilExecutionAuthority
}

data class CouncilExecutionAuthority(
    val readers: Set<Int> = emptySet(),
    val writers: Set<Int> = emptySet(),
    val noticeWriters: Set<Int> = emptySet(),
    val rulerGeneralId: Int? = null,
    val rulerRevision: String? = null,
) {
    init {
        require(readers.all { it > 0 } && writers.all { it in readers } && noticeWriters.all { it in writers })
        require((rulerGeneralId == null) == (rulerRevision == null))
        require(rulerGeneralId == null || rulerGeneralId in readers)
    }
}

/** A dedicated social channel without catalog costs, decision turns or fabricated InputResolved events. */
class CouncilHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val posts: BoardPostRepository,
    private val authority: CouncilExecutionAuthoritySource,
) {
    fun handle(command: CouncilInput): CommandLifecycleResult {
        fun reject(code: String, reason: String) = result(command, false, code, reason)
        if (world.ruleProfile != RuleProfile.HWIHA)
            return reject("FORBIDDEN", "이 세계에서는 회의실을 사용할 수 없습니다.")
        if (!command.requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
            return reject("INVALID_REQUEST", "접수 식별자가 올바르지 않습니다.")
        val actor = world.getGeneralById(command.generalId)
            ?: return reject("FORBIDDEN", "현재 소유 장수를 확인할 수 없습니다.")
        if (command.ownerUserId <= 0 || actor.userId?.toLongOrNull() != command.ownerUserId.toLong() || actor.npcState == 5 ||
            actor.nationId <= 0 || actor.nationId != command.nationId || world.getNationById(actor.nationId) == null)
            return reject("FORBIDDEN", "현재 본인의 소속 회의실만 사용할 수 있습니다.")
        val request = CouncilRequestCodec.parse(command.action, command.argJson)
            ?: return reject("INVALID_REQUEST", "회의실 입력이 올바르지 않습니다.")
        val proof = try { authority.read(actor.nationId) } catch (_: RuntimeException) {
            return reject("STATE_UNAVAILABLE", "회의실 권한 근거를 확인할 수 없습니다.")
        }
        when (request) {
            is CouncilRequest.PostArticle -> {
                val secret = request.room == "SECRET"
                if (secret && actor.id !in proof.writers)
                    return reject("SECRET_ACCESS_REQUIRED", "기밀실 작성 권한이 없습니다.")
                if (request.kind == "NOTICE" && actor.id !in proof.noticeWriters)
                    return reject("NOTICE_ACCESS_REQUIRED", "공지는 기밀실 참여자만 쓸 수 있습니다.")
                request.operationId?.let { operationId ->
                    if (world.getOperationById(operationId)?.nationId != actor.nationId)
                        return reject("FORBIDDEN", "현재 소속의 작전만 연결할 수 있습니다.")
                }
                recorder.recordBoardPostInsert(linkedMapOf(
                    "nation_id" to actor.nationId, "is_secret" to secret,
                    "author_general_id" to actor.id, "author_name" to actor.name,
                    "title" to request.title, "content_html" to request.contentHtml,
                    "kind" to request.kind.lowercase(Locale.ROOT), "vote_id" to null,
                    "operation_id" to request.operationId,
                ))
            }
            is CouncilRequest.PostComment, is CouncilRequest.MarkRead -> {
                val articleId = when (request) {
                    is CouncilRequest.PostComment -> request.articleId
                    is CouncilRequest.MarkRead -> request.articleId
                    else -> error("도달할 수 없는 입력입니다.")
                }
                // Restrict room access in SQL before reading a SECRET body.
                val article = posts.findAccessibleCouncilPost(articleId, actor.nationId, actor.id in proof.readers)
                    ?: return reject("FORBIDDEN", "현재 소속에서 접근할 수 있는 글이 아닙니다.")
                if (article.nationId != actor.nationId || article.id != articleId ||
                    (article.isSecret && actor.id !in proof.readers))
                    return reject("FORBIDDEN", "현재 소속에서 접근할 수 있는 글이 아닙니다.")
                if (request is CouncilRequest.PostComment) {
                    if (article.isSecret && actor.id !in proof.writers)
                        return reject("SECRET_ACCESS_REQUIRED", "기밀실 작성 권한이 없습니다.")
                    recorder.recordBoardCommentInsert(linkedMapOf(
                        "post_id" to articleId, "nation_id" to actor.nationId, "is_secret" to article.isSecret,
                        "author_general_id" to actor.id, "author_name" to actor.name, "content_text" to request.text,
                    ))
                } else if (article.isSecret) {
                    recorder.recordBoardReadInsert(linkedMapOf("post_id" to articleId, "general_id" to actor.id))
                }
            }
            is CouncilRequest.GrantAccess, is CouncilRequest.RevokeAccess -> {
                if (proof.rulerGeneralId != actor.id || proof.rulerRevision == null ||
                    proof.rulerRevision != command.authorityRevision)
                    return reject("FORBIDDEN", "현재 군주만 기밀실 참여자를 지정하거나 회수할 수 있습니다.")
                val targetId = when (request) {
                    is CouncilRequest.GrantAccess -> request.targetGeneralId
                    is CouncilRequest.RevokeAccess -> request.targetGeneralId
                    else -> error("도달할 수 없는 입력입니다.")
                }
                val target = world.getGeneralById(targetId)
                if (target == null || target.nationId != actor.nationId || target.npcState == 5)
                    return reject("FORBIDDEN", "현재 같은 소속의 장수만 지정할 수 있습니다.")
                val state = try { CouncilDesignationCodec.decode(PersistedMetaJson.raw(
                    world.getState().meta[CouncilDesignationCodec.META_KEY])) } catch (_: RuntimeException) {
                    return reject("STATE_UNAVAILABLE", "기밀실 지정 이력을 확인할 수 없습니다.")
                }
                val expected = when (request) {
                    is CouncilRequest.GrantAccess -> request.expectedRevision
                    is CouncilRequest.RevokeAccess -> request.expectedRevision
                    else -> error("도달할 수 없는 입력입니다.")
                }
                if (expected != state?.revision)
                    return reject("REVISION_CONFLICT", "참여자 정보가 바뀌었습니다. 새로고침해 주세요.")
                val previous = state?.grants.orEmpty()
                val existing = previous.singleOrNull { it.nationId == actor.nationId &&
                    it.targetGeneralId == targetId && it.revokedByRequestId == null }
                val changed = if (request is CouncilRequest.GrantAccess) {
                    if (existing?.issuerGeneralId == actor.id && existing.issuerRevision == proof.rulerRevision)
                        return result(command, true)
                    // An explicit new-ruler grant creates a new receipt and preserves history without automatic inheritance.
                    val history = if (existing == null) previous else previous.map {
                        if (it.id == existing.id) it.copy(revokedByRequestId = command.requestId) else it
                    }
                    history + CouncilDesignation(command.requestId, actor.nationId, actor.id,
                        proof.rulerRevision, targetId, command.requestId)
                } else {
                    if (existing == null) return result(command, true)
                    previous.map { if (it.id == existing.id) it.copy(revokedByRequestId = command.requestId) else it }
                }
                // Retain designation and read history without deciding succession inheritance or automatic revocation.
                val value = MetaJson.decode(CouncilDesignationCodec.encode(CouncilDesignationState(command.requestId, changed)))
                world.setGameEnvValue(CouncilDesignationCodec.META_KEY, value)
                recorder.recordKv("game_env", "game_env", CouncilDesignationCodec.META_KEY, value)
            }
        }
        return result(command, true)
    }

    private fun result(command: CouncilInput, ok: Boolean,
                       code: String? = null, reason: String? = null) = CommandLifecycleResult(
        type = if (ok) "executionApplied" else "executionRejected", ok = ok,
        commandKind = "IMMEDIATE", actionCode = "CouncilInput:${command.action}",
        generalId = command.generalId, code = code, reason = reason,
    )
}
