package opensamguk.common.log
class ActionLogger(private val generalId: Int? = null, private val nationId: Int? = null) {
    private val logs = mutableListOf<LogEntryDraft>()
    fun flush(): List<LogEntryDraft> { val i = logs.toList(); logs.clear(); return i }
    fun rollback(): List<LogEntryDraft> = flush()
    fun pushGeneralHistoryLog(text: String, format: LogFormat = LogFormat.YEAR_MONTH) = pushOne(text) { generalDraft(LogCategory.HISTORY, it, format) }
    fun pushGeneralHistoryLog(text: List<String>, format: LogFormat = LogFormat.YEAR_MONTH) = pushMany(text) { generalDraft(LogCategory.HISTORY, it, format) }
    fun pushGeneralActionLog(text: String, format: LogFormat = LogFormat.MONTH) = pushOne(text) { generalDraft(LogCategory.ACTION, it, format) }
    fun pushGeneralActionLog(text: List<String>, format: LogFormat = LogFormat.MONTH) = pushMany(text) { generalDraft(LogCategory.ACTION, it, format) }
    fun pushGeneralBattleResultLog(text: String, format: LogFormat = LogFormat.RAWTEXT) = pushOne(text) { generalDraft(LogCategory.BATTLE_BRIEF, it, format) }
    fun pushGeneralBattleDetailLog(text: String, format: LogFormat = LogFormat.PLAIN) = pushOne(text) { generalDraft(LogCategory.BATTLE_DETAIL, it, format) }
    fun pushNationHistoryLog(text: String, format: LogFormat = LogFormat.YEAR_MONTH, nationId: Int? = this.nationId) {
        if (nationId == null || nationId == 0) return
        pushOne(text) { LogEntryDraft(LogScope.NATION, LogCategory.HISTORY, it, nationId = nationId, format = format) }
    }
    fun pushGlobalHistoryLog(text: String, format: LogFormat = LogFormat.YEAR_MONTH) = pushOne(text) { LogEntryDraft(LogScope.SYSTEM, LogCategory.HISTORY, it, format = format) }
    fun pushGlobalActionLog(text: String, format: LogFormat = LogFormat.MONTH) = pushOne(text) { LogEntryDraft(LogScope.SYSTEM, LogCategory.SUMMARY, it, format = format) }
    private fun generalDraft(category: LogCategory, msg: String, format: LogFormat) =
        LogEntryDraft(LogScope.GENERAL, category, msg, generalId = generalId, format = format)
    private fun pushOne(text: String, builder: (String) -> LogEntryDraft) { if (text.isEmpty()) return; logs.add(builder(text)) }
    private fun pushMany(text: List<String>, builder: (String) -> LogEntryDraft) { for (item in text) if (item.isNotEmpty()) logs.add(builder(item)) }
}
