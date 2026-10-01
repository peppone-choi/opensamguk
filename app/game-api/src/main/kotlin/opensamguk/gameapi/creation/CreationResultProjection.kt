package opensamguk.gameapi.creation

import opensamguk.gameapi.dto.GeneralCreationErrorDto
import opensamguk.gameapi.dto.GeneralCreationResultDto

/** Receipt ownership is checked before an internal daemon result can be returned. */
data class CreationReceiptView(
    val accountId: Long,
    val worldId: Int,
    val clientRequestId: String,
    val internalCommandRequestId: String,
)

data class CreationTerminalView(
    val internalCommandRequestId: String,
    val committedWorldVersion: Long,
    val generalId: Int?,
    val errorCode: String?,
    val errorMessage: String?,
)

data class CreationOwnedGeneralView(val worldId: Int, val generalId: Int, val accountId: Long)

sealed interface CreationResultProjection {
    data object NotFound : CreationResultProjection
    data class Visible(val result: GeneralCreationResultDto) : CreationResultProjection
}

object CreationResultProjector {
    fun project(
        callerAccountId: Long,
        processWorldId: Int,
        requestedClientRequestId: String,
        receipt: CreationReceiptView?,
        terminal: CreationTerminalView?,
        owner: CreationOwnedGeneralView?,
    ): CreationResultProjection {
        if (receipt == null || receipt.accountId != callerAccountId ||
            receipt.worldId != processWorldId || receipt.clientRequestId != requestedClientRequestId)
            return CreationResultProjection.NotFound
        fun visible(status: String, generalId: Int? = null, error: GeneralCreationErrorDto? = null) =
            CreationResultProjection.Visible(GeneralCreationResultDto(requestId = requestedClientRequestId,
                worldId = processWorldId, status = status, generalId = generalId, error = error))
        if (terminal == null || terminal.internalCommandRequestId != receipt.internalCommandRequestId ||
            terminal.committedWorldVersion < 0) return visible("PENDING")
        val error = terminal.errorCode
        if (error != null) return visible("REJECTED", error = GeneralCreationErrorDto(error,
            terminal.errorMessage.orEmpty()))
        val createdId = terminal.generalId ?: return visible("PENDING")
        if (owner?.worldId != processWorldId || owner.generalId != createdId ||
            owner.accountId != callerAccountId) return visible("PENDING")
        return visible("CREATED", generalId = createdId)
    }
}

/** Keeps the receipt lookup ahead of every internal-result or owner query. */
interface CreationResultSources {
    fun receipt(accountId: Long, worldId: Int, clientRequestId: String): CreationReceiptView?
    fun terminal(worldId: Int, internalCommandRequestId: String): CreationTerminalView?
    fun owner(worldId: Int, generalId: Int): CreationOwnedGeneralView?
}

class CreationResultReader(private val sources: CreationResultSources) {
    fun read(accountId: Long, processWorldId: Int, clientRequestId: String): CreationResultProjection {
        val receipt = sources.receipt(accountId, processWorldId, clientRequestId)
            ?: return CreationResultProjection.NotFound
        if (receipt.accountId != accountId || receipt.worldId != processWorldId ||
            receipt.clientRequestId != clientRequestId) return CreationResultProjection.NotFound
        val terminal = sources.terminal(processWorldId, receipt.internalCommandRequestId)
        val owner = terminal?.generalId?.takeIf { terminal.errorCode == null }
            ?.let { sources.owner(processWorldId, it) }
        return CreationResultProjector.project(accountId, processWorldId, clientRequestId,
            receipt, terminal, owner)
    }
}
