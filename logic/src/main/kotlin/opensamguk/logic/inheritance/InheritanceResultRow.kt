package opensamguk.logic.inheritance

data class InheritanceResultRow(
    val serverID: Int,
    val ownerID: Int,
    val generalID: Int,
    val year: Int,
    val month: Int,
    val valueJson: String,
)
