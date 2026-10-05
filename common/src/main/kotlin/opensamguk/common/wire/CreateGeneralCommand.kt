package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Account-scoped creation receipt maps its public UUID to the envelope's internal request ID. */
@Serializable
@SerialName("createGeneral")
data class CreateGeneral(
    val accountId: Int,
    val worldId: Int,
    val clientRequestId: String,
    val choiceKind: String,
    val custom: CreationCustomChoice? = null,
    val historicalGeneralId: Int? = null,
) : TurnDaemonCommand() {
    override val type: String get() = "createGeneral"
}
