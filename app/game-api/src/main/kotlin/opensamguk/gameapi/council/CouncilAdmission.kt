package opensamguk.gameapi.council

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.OperationReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.sanitize.HtmlSanitizer
import opensamguk.logic.council.CouncilRequest
import opensamguk.logic.council.CouncilRequestCodec
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.WorldRuleProfile
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class CouncilAdmission(
    private val reader: CouncilReader,
    private val posts: BoardPostReadRepository,
    private val operations: OperationReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun command(userId: Long, action: String, raw: String): TurnDaemonCommand.CouncilInput {
        if (userId !in 1..Int.MAX_VALUE.toLong()) fail(403, "FORBIDDEN", "본인의 장수가 필요합니다.")
        val session = reader.session(userId)
        val actor = session.actor
        if (actor.nationId <= 0) fail(403, "NO_AFFILIATION", "소속 세력이 있어야 회의실을 이용할 수 있습니다.")
        if (session.world?.let { WorldRuleProfile.resolve(it.config) } != RuleProfile.HWIHA)
            fail(503, "STATE_UNAVAILABLE", "이 세계의 회의실 정책을 확인할 수 없습니다.")
        val proof = session.authority
        val parsed = CouncilRequestCodec.parse(action, raw)
            ?: fail(400, "INVALID_REQUEST", "회의실 입력이 올바르지 않습니다.")
        val request = when (parsed) {
            is CouncilRequest.PostArticle -> {
                if (parsed.room == "SECRET" && actor.id !in proof.writers) secretDenied(proof.complete)
                if (parsed.kind == "NOTICE" && actor.id !in proof.noticeWriters)
                    fail(if (proof.complete) 403 else 503,
                        if (proof.complete) "NOTICE_ACCESS_REQUIRED" else "STATE_UNAVAILABLE",
                        "공지 작성 권한을 확인할 수 없습니다.")
                parsed.operationId?.let { operationId ->
                    val op = operations.operationsOf(actor.nationId).singleOrNull { it.id == operationId }
                    if (op == null || op.worldId != actor.worldId || op.nationId != actor.nationId)
                        fail(403, "FORBIDDEN", "현재 소속의 작전만 연결할 수 있습니다.")
                }
                val sanitized = parsed.copy(contentHtml = HtmlSanitizer.sanitize(parsed.contentHtml))
                CouncilRequestCodec.parse(action, CouncilRequestCodec.encode(sanitized))
                    ?: fail(400, "INVALID_REQUEST", "제목이나 내용을 쓰세요.")
            }
            is CouncilRequest.PostComment, is CouncilRequest.MarkRead -> {
                val articleId = when (parsed) {
                    is CouncilRequest.PostComment -> parsed.articleId
                    is CouncilRequest.MarkRead -> parsed.articleId
                    else -> error("도달할 수 없는 입력입니다.")
                }
                val parent = posts.councilArticle(actor.nationId, articleId, actor.id in proof.readers)
                    ?: fail(403, "FORBIDDEN", "현재 소속에서 접근할 수 있는 글이 아닙니다.")
                if (parent.worldId != actor.worldId || parent.nationId != actor.nationId || parent.id != articleId ||
                    (parent.isSecret && actor.id !in proof.readers))
                    fail(403, "FORBIDDEN", "현재 소속에서 접근할 수 있는 글이 아닙니다.")
                if (parsed is CouncilRequest.PostComment && parent.isSecret && actor.id !in proof.writers)
                    secretDenied(proof.complete)
                parsed
            }
            is CouncilRequest.GrantAccess, is CouncilRequest.RevokeAccess -> {
                if (proof.rulerGeneralId != actor.id || proof.rulerRevision == null)
                    fail(if (proof.rulerRevision == null) 503 else 403,
                        if (proof.rulerRevision == null) "STATE_UNAVAILABLE" else "FORBIDDEN",
                        "현재 군주만 기밀실 참여자를 지정하거나 회수할 수 있습니다.")
                val targetId = when (parsed) {
                    is CouncilRequest.GrantAccess -> parsed.targetGeneralId
                    is CouncilRequest.RevokeAccess -> parsed.targetGeneralId
                    else -> error("도달할 수 없는 입력입니다.")
                }
                if (session.people.none { it.id == targetId && it.nationId == actor.nationId && it.npcState != 5 })
                    fail(403, "FORBIDDEN", "현재 같은 소속의 장수만 지정할 수 있습니다.")
                val expected = when (parsed) {
                    is CouncilRequest.GrantAccess -> parsed.expectedRevision
                    is CouncilRequest.RevokeAccess -> parsed.expectedRevision
                    else -> error("도달할 수 없는 입력입니다.")
                }
                if (expected != proof.designationRevision)
                    fail(409, "REVISION_CONFLICT", "참여자 정보가 바뀌었습니다. 새로고침해 주세요.")
                parsed
            }
        }
        return TurnDaemonCommand.CouncilInput("", actor.id, userId.toInt(), actor.nationId, action,
            CouncilRequestCodec.encode(request), proof.rulerRevision)
    }

    /** publishImmediate의 다른 내부 호출자도 실제 소유·소속 근거를 다시 확인한다. */
    fun rebind(command: TurnDaemonCommand.CouncilInput, userId: Int): TurnDaemonCommand.CouncilInput {
        val current = this.command(userId.toLong(), command.action, command.argJson)
        if (current.generalId != command.generalId || current.nationId != command.nationId || command.ownerUserId != userId)
            fail(403, "FORBIDDEN", "본인의 현재 소속 회의실만 사용할 수 있습니다.")
        return current
    }

    private fun secretDenied(complete: Boolean): Nothing = fail(if (complete) 403 else 503,
        if (complete) "SECRET_ACCESS_REQUIRED" else "STATE_UNAVAILABLE", "기밀실 작성 권한을 확인할 수 없습니다.")
    private fun fail(status: Int, code: String, reason: String): Nothing = throw CouncilReadFailure(status, code, reason)
}

@Service
class CouncilSubmission(private val admission: CouncilAdmission, private val reserve: CommandReserveService) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun submit(userId: Long, action: String, raw: String): CouncilReceipt {
        val command = admission.command(userId, action, raw)
        val receipt = reserve.publishImmediate(command, userId.toInt())
        return CouncilReceipt(receipt.requestId)
    }
}

data class CouncilReceipt(val requestId: String, val status: String = "ACCEPTED")
