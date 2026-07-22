package opensamguk.gameapi.reserve

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import opensamguk.infra.persistence.CommandResultRow
import java.time.Instant

object CommandTerminalResultFactory {
    fun acceptedRow(
        worldId: WorldId,
        requestId: String,
        sentAt: Instant,
        type: String,
        commandKind: CommandKind,
        actionCode: String?,
        generalId: Int?,
        turnIdx: Int?,
    ): CommandResultRow {
        val result = CommandLifecycleResult(
            type = type,
            ok = true,
            commandKind = commandKind.name,
            actionCode = actionCode,
            generalId = generalId,
            turnIdx = turnIdx,
        )
        val envelope = TurnDaemonEventEnvelope(
            requestId = requestId,
            sentAt = sentAt.toString(),
            event = TurnDaemonEvent.CommandResult(result),
        )
        return CommandResultRow(
            requestId = requestId,
            eventId = "command-result:${worldId.value}:$requestId:1",
            resultType = result.type,
            ok = result.ok,
            committedWorldVersion = 0,
            payloadSchemaVersion = 1,
            envelopeJson = WireJson.encodeToString(TurnDaemonEventEnvelope.serializer(), envelope),
            sentAt = sentAt,
        )
    }
}
