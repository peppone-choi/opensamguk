package opensamguk.common.log

private const val ICON_CIRCLE = "●"   // U+25CF
private const val ICON_DIAMOND = "◆"  // U+25C6
private const val ICON_STAR = "★"     // U+2605
fun formatLogText(text: String, format: LogFormat, year: Int, month: Int, phase: Int? = null): String = when (format) {
    LogFormat.RAWTEXT -> text
    LogFormat.PLAIN -> "<C>$ICON_CIRCLE</>$text"
    LogFormat.YEAR_MONTH -> "<C>$ICON_CIRCLE</>${year}년 ${month}월${phaseSuffix(phase)}:$text"
    LogFormat.YEAR -> "<C>$ICON_CIRCLE</>${year}년:$text"
    LogFormat.MONTH -> "<C>$ICON_CIRCLE</>${month}월${phaseSuffix(phase)}:$text"
    LogFormat.EVENT_PLAIN -> "<S>$ICON_DIAMOND</>$text"
    LogFormat.EVENT_YEAR_MONTH -> "<S>$ICON_DIAMOND</>${year}년 ${month}월${phaseSuffix(phase)}:$text"
    LogFormat.NOTICE -> "<R>$ICON_STAR</>$text"
    LogFormat.NOTICE_YEAR_MONTH -> "<R>$ICON_STAR</>${year}년 ${month}월${phaseSuffix(phase)}:$text"
}

private fun phaseSuffix(phase: Int?): String = when (phase) {
    1 -> " 상순"
    2 -> " 중순"
    3 -> " 하순"
    else -> ""
}
