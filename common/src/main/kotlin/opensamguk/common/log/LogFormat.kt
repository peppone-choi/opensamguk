package opensamguk.common.log

enum class LogFormat(val code: Int) {
    RAWTEXT(0), PLAIN(1), YEAR_MONTH(2), YEAR(3), MONTH(4),
    EVENT_PLAIN(5), EVENT_YEAR_MONTH(6), NOTICE(7), NOTICE_YEAR_MONTH(8);
    companion object { fun fromCode(code: Int): LogFormat = entries.first { it.code == code } }
}
