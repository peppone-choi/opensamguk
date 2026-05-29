package opensamguk.common.log
enum class LogScope { SYSTEM, NATION, GENERAL, USER }
enum class LogCategory { HISTORY, SUMMARY, ACTION, BATTLE_BRIEF, BATTLE_DETAIL, USER }
data class LogEntryDraft(
    val scope: LogScope, val category: LogCategory, val text: String,
    val generalId: Int? = null, val nationId: Int? = null, val userId: Int? = null,
    val subType: String? = null, val meta: Map<String, Any?>? = null, val format: LogFormat? = null,
)
data class LogEntryRecord(
    val scope: LogScope, val category: LogCategory, val text: String, val year: Int, val month: Int,
    val generalId: Int? = null, val nationId: Int? = null, val userId: Int? = null,
    val subType: String? = null, val meta: Map<String, Any?>? = null, val createdAt: java.time.Instant? = null,
)
data class LogContext(val year: Int, val month: Int, val at: java.time.Instant? = null)
