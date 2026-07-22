package opensamguk.gameapi.admin

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.logic.util.jsonEncode
import org.springframework.stereotype.Service

@Service
class AdminGeneralModerationService(
    private val commands: CommandReserveService,
) {
    fun apply(action: String, generalIds: List<Int>, message: String?, actorGeneralId: Int?): Result {
        val ids = generalIds.distinct().filter { it > 0 }
        require(ids.isNotEmpty()) { "대상 장수를 선택하세요." }
        val actorId = requireNotNull(actorGeneralId) { "조치를 실행할 관리자 장수가 없습니다." }
        val requestIds = mutableListOf<String>()

        when (action) {
            "unblock", "block1", "block2", "block3", "infiniteKillturn",
            "dex1", "dex2", "dex3", "dex4", "dex5" -> {
                requestIds += commands.publishImmediate(
                    TurnDaemonCommand.AdminGeneralModeration(
                        actorGeneralId = actorId,
                        generalIds = ids,
                        action = action,
                    ),
                ).requestId
                dexMessage(action)?.let { text -> requestIds += sendMessages(actorId, ids, text) }
            }
            "allowAccess", "denyAccess", "allowAccessAll", "denyAccessAll" -> {
                requestIds += commands.publishImmediate(
                    TurnDaemonCommand.AdminGeneralModeration(
                        actorGeneralId = actorId,
                        generalIds = ids,
                        action = action.removeSuffix("All"),
                    ),
                ).requestId
            }
            "forceDeath" -> {
                ids.forEach { requestIds += commands.reserve(it, "휴식", turnIdx = 0, argJson = "{}").requestId }
                requestIds += commands.publishImmediate(
                    TurnDaemonCommand.AdminGeneralModeration(
                        actorGeneralId = actorId,
                        generalIds = ids,
                        action = action,
                    ),
                ).requestId
            }
            "resign" -> ids.forEach { requestIds += commands.reserve(it, "che_하야", turnIdx = 0, argJson = "{}").requestId }
            "wanderDismiss" -> ids.forEach {
                requestIds += commands.reserve(it, "che_방랑", turnIdx = 0, argJson = "{}").requestId
                requestIds += commands.reserve(it, "che_해산", turnIdx = 1, argJson = "{}").requestId
            }
            "sendMessage" -> {
                val text = message.orEmpty()
                require(text.length <= 255) { "메세지는 255자 이하여야 합니다." }
                requestIds += sendMessages(actorId, ids, text)
            }
            else -> throw IllegalArgumentException("지원하지 않는 관리자 조치입니다: $action")
        }
        return Result(action = action, affected = ids.size, requestIds = requestIds)
    }

    private fun sendMessages(actorGeneralId: Int, generalIds: List<Int>, text: String): List<String> =
        generalIds.map { generalId ->
            commands.reserve(
                generalId = actorGeneralId,
                actionCode = "sendMessage",
                argJson = jsonEncode(linkedMapOf("mailbox" to generalId, "text" to text)),
            ).requestId
        }

    private fun dexMessage(action: String): String? = when (action) {
        "dex1" -> "보병숙련도+10000 지급!"
        "dex2" -> "궁병숙련도+10000 지급!"
        "dex3" -> "기병숙련도+10000 지급!"
        "dex4" -> "귀병숙련도+10000 지급!"
        "dex5" -> "차병숙련도+10000 지급!"
        else -> null
    }

    data class Result(val action: String, val affected: Int, val requestIds: List<String> = emptyList())
}
