package opensamguk.common.log
internal fun shouldDropEntry(entry: LogEntryDraft): Boolean {
    if (entry.scope == LogScope.GENERAL && (entry.generalId == null || entry.generalId == 0)) return true
    if (entry.scope == LogScope.NATION && (entry.nationId == null || entry.nationId == 0)) return true
    if (entry.scope == LogScope.USER && (entry.userId == null || entry.userId == 0)) return true
    return false
}
fun finalizeLogEntry(entry: LogEntryDraft, context: LogContext): LogEntryRecord? {
    if (shouldDropEntry(entry)) return null
    val format = entry.format ?: LogFormat.RAWTEXT
    val text = formatLogText(entry.text, format, context.year, context.month)
    return LogEntryRecord(entry.scope, entry.category, text, context.year, context.month,
        entry.generalId, entry.nationId, entry.userId, entry.subType, entry.meta, context.at)
}
