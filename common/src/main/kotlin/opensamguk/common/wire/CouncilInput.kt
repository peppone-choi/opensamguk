package opensamguk.common.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Dedicated council intake; server-bound identity and receipt are checked again during execution. */
@Serializable
@SerialName("councilInput")
data class CouncilInput(
    val requestId: String,
    val generalId: Int,
    val ownerUserId: Int,
    val nationId: Int,
    val action: String,
    val argJson: String,
    val authorityRevision: String?,
) : TurnDaemonCommand() {
    override val type: String get() = "councilInput"
}

