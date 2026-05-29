package opensamguk.common.log
class UserLogger(private val userId: Int) {
    private val logs = mutableListOf<LogEntryDraft>()
    fun flush(): List<LogEntryDraft> { val i = logs.toList(); logs.clear(); return i }
    fun rollback(): List<LogEntryDraft> = flush()
    fun push(text: String, subType: String) { if (text.isEmpty()) return; logs.add(LogEntryDraft(LogScope.USER, LogCategory.USER, text, userId = userId, subType = subType)) }
    fun push(text: List<String>, subType: String) { for (item in text) if (item.isNotEmpty()) logs.add(LogEntryDraft(LogScope.USER, LogCategory.USER, item, userId = userId, subType = subType)) }
}
