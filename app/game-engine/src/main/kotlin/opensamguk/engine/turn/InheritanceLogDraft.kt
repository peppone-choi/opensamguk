package opensamguk.engine.turn

/**
 * Raw inheritance log intent captured by [ChangeRecorder].
 * Year/month are stamped at flush time by [opensamguk.engine.flush.DatabaseHooks]
 * (mirrors [LogEntryDraft] → [opensamguk.infra.persistence.LogRow] finalize).
 */
data class InheritanceLogDraft(
    val ownerID: Int,
    val text: String,
    val tag: String,
)
