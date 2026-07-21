package opensamguk.engine.intake

import opensamguk.common.wire.AcceptDiplomaticMessageFail
import opensamguk.common.wire.AcceptDiplomaticMessageOk
import opensamguk.common.wire.DeclineDiplomaticMessageFail
import opensamguk.common.wire.DeclineDiplomaticMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.logic.actions.intake.SecretPermission
import opensamguk.logic.actions.nation.InstantNationCommandRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.util.jsonEncode
import java.time.Instant

class DiplomaticMessageHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val processNationCommand: ProcessNationCommand?,
    private val messageReader: ((messageId: Int) -> MessageSnapshot?)?,
    private val nowProvider: () -> Instant = Instant::now,
) {
    fun handleAccept(command: TurnDaemonCommand.AcceptDiplomaticMessage): TurnDaemonCommandResult {
        val validated = validate(command.messageId, command.generalId)
        if (validated is Validation.Failed) {
            return AcceptDiplomaticMessageFail(messageId = command.messageId, reason = validated.reason)
        }
        validated as Validation.Allowed

        val built = buildAcceptCommand(validated.message)
        val invalidationBody = invalidationBody(validated.message)
        val processor = processNationCommand
            ?: return AcceptDiplomaticMessageFail(messageId = command.messageId, reason = UNAVAILABLE_REASON)

        return when (val result = processor.processInstant(command.generalId, built)) {
            ProcessNationCommand.InstantResult.Allowed -> {
                recorder.recordMessageInvalidate(command.messageId, INVALIDATED_AT, invalidationBody)
                AcceptDiplomaticMessageOk(messageId = command.messageId)
            }
            is ProcessNationCommand.InstantResult.Denied ->
                AcceptDiplomaticMessageFail(messageId = command.messageId, reason = result.reason)
        }
    }

    fun handleDecline(command: TurnDaemonCommand.DeclineDiplomaticMessage): TurnDaemonCommandResult {
        val validated = validate(command.messageId, command.generalId)
        if (validated is Validation.Failed) {
            return DeclineDiplomaticMessageFail(messageId = command.messageId, reason = validated.reason)
        }
        validated as Validation.Allowed

        recorder.recordMessageInvalidate(
            command.messageId,
            INVALIDATED_AT,
            invalidationBody(validated.message),
        )
        return DeclineDiplomaticMessageOk(messageId = command.messageId)
    }

    private fun validate(messageId: Int, generalId: Int): Validation {
        val message = messageReader?.invoke(messageId)
            ?: return Validation.Failed(NOT_FOUND_REASON)
        val action = message.option["action"] as? String
        if (message.type != "diplomacy" || InstantNationCommandRegistry.acceptCommandKeyFor(action.orEmpty()) == null) {
            return Validation.Failed(INVALID_REASON)
        }
        if (recorder.messageInvalidates().any { it.id == messageId } ||
            SecretPermission.phpTruthy(message.option["used"]) ||
            message.validUntil.isBefore(nowProvider())
        ) {
            return Validation.Failed(INVALID_REASON)
        }
        if (message.mailbox != message.destNationId + MessageHandler.MAILBOX_NATIONAL) {
            return Validation.Failed(MAILBOX_REASON)
        }

        val actor = world.getGeneralById(generalId)
        if (actor == null ||
            actor.nationId != message.destNationId ||
            SecretPermission.check(PerTurnOverlay.toLogicGeneral(actor), checkSecretLimit = false) < 4
        ) {
            return Validation.Failed(AUTHORITY_REASON)
        }
        return Validation.Allowed(message)
    }

    private fun buildAcceptCommand(message: MessageSnapshot): ChosenCommand {
        val action = message.option["action"] as String
        val commandKey = checkNotNull(InstantNationCommandRegistry.acceptCommandKeyFor(action))
        val args = linkedMapOf<String, Any?>(
            "destNationID" to message.srcNationId,
            "destGeneralID" to message.srcGeneralId,
        )
        if (action == "no_aggression") {
            args["year"] = message.option["year"]
            args["month"] = message.option["month"]
        }
        return ChosenCommand(commandKey, args)
    }

    private fun invalidationBody(message: MessageSnapshot): String {
        val option = LinkedHashMap(message.option)
        option["used"] = true
        option["invalid"] = true
        return jsonEncode(
            linkedMapOf(
                "src" to message.srcArray,
                "dest" to message.destArray,
                "text" to message.text,
                "option" to option,
            ),
        )
    }

    private sealed interface Validation {
        data class Allowed(val message: MessageSnapshot) : Validation

        data class Failed(val reason: String) : Validation
    }

    private companion object {
        const val NOT_FOUND_REASON = "존재하지 않는 메시지입니다."
        const val INVALID_REASON = "유효하지 않은 외교서신입니다."
        const val MAILBOX_REASON = "송신자가 외교서신을 처리할 수 없습니다."
        const val AUTHORITY_REASON = "해당 국가의 외교권자가 아닙니다."
        const val UNAVAILABLE_REASON = "처리할 수 없습니다."
        const val INVALIDATED_AT = "2000-12-31 00:00:00"
    }
}
