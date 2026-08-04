package opensamguk.engine.intake

import opensamguk.common.wire.AcceptRaiseInvaderMessageFail
import opensamguk.common.wire.AcceptRaiseInvaderMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.message.MessageTarget
import opensamguk.logic.util.jsonEncode
import opensamguk.logic.world.RaiseInvaderSpec
import java.time.Instant

class RaiseInvaderMessageHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val messageReader: ((Int) -> MessageSnapshot?)?,
    private val raiseInvader: (RaiseInvaderSpec) -> Int,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun handle(command: TurnDaemonCommand.AcceptRaiseInvaderMessage): TurnDaemonCommandResult {
        val message = messageReader?.invoke(command.messageId)
            ?: return failure(command.messageId, INVALID_MESSAGE)
        if (message.type != "private" || message.option["action"] != "raiseInvader") {
            return reject(message, INVALID_MESSAGE)
        }
        if (recorder.messageInvalidates().any { it.id == command.messageId } ||
            SecretPermission.phpTruthy(message.option["used"])
        ) {
            return reject(message, ALREADY_USED)
        }
        if (message.mailbox != message.destGeneralId) {
            return reject(message, INVALID_SENDER)
        }
        if (message.mailbox != command.generalId || world.getGeneralById(command.generalId) == null) {
            return reject(message, INVALID_RECEIVER)
        }
        if (((world.getState().meta["isunited"] as? Number)?.toInt() ?: 0) != 2) {
            return reject(message, NOT_UNITED)
        }
        val args = (message.option["args"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toDouble() }
            ?.takeIf { it.size == 4 }
            ?: return reject(message, INVALID_MESSAGE)
        val count = raiseInvader(
            RaiseInvaderSpec(
                npcEachCount = args[0],
                specAvg = args[1],
                tech = args[2],
                dex = args[3],
            ),
        )
        return AcceptRaiseInvaderMessageOk(
            messageId = command.messageId,
            invaderNationCount = count,
        )
    }

    private fun failure(messageId: Int, reason: String): AcceptRaiseInvaderMessageFail =
        AcceptRaiseInvaderMessageFail(messageId = messageId, reason = reason)

    private fun reject(message: MessageSnapshot, reason: String): AcceptRaiseInvaderMessageFail {
        val destination = message.destArray ?: emptyMap()
        recorder.recordMessageInsert(
            mailbox = message.destGeneralId,
            type = "private",
            srcId = 0,
            destId = message.destGeneralId,
            time = MessageHandler.formatPhpDate(nowProvider()),
            validUntil = MessageHandler.VALID_UNTIL_SENTINEL,
            bodyJson = jsonEncode(
                linkedMapOf(
                    "src" to MessageTarget.buildSystemTarget().toArray(),
                    "dest" to destination,
                    "text" to "$reason 이민족 등장 불가.",
                    "option" to linkedMapOf<String, Any?>(),
                ),
            ),
        )
        val general = world.getGeneralById(message.destGeneralId)
        if (general != null && (general.meta["newmsg"] as? Number)?.toInt() != 1) {
            val after = general.copy(meta = LinkedHashMap(general.meta).apply { this["newmsg"] = 1 })
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(general), PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
        }
        return failure(message.id, reason)
    }

    companion object {
        const val ALREADY_USED = "이미 사용하였습니다."
        const val INVALID_SENDER = "송신자가 메시지를 처리할 수 없습니다."
        const val INVALID_RECEIVER = "올바른 수신자가 아닙니다."
        const val NOT_UNITED = "천하통일이 되지 않았습니다."
        const val INVALID_MESSAGE = "올바르지 않은 메시지입니다."
    }
}
