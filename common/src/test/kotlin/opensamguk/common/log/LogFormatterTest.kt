package opensamguk.common.log
import kotlin.test.Test; import kotlin.test.assertEquals
class LogFormatterTest {
    @Test fun `glyph codepoints are exact`() {
        assertEquals(0x25CF, "●".codePointAt(0)); assertEquals(0x25C6, "◆".codePointAt(0)); assertEquals(0x2605, "★".codePointAt(0))
    }
    @Test fun `enum codes are 0 to 8`() {
        assertEquals(0, LogFormat.RAWTEXT.code); assertEquals(8, LogFormat.NOTICE_YEAR_MONTH.code); assertEquals(LogFormat.YEAR_MONTH, LogFormat.fromCode(2))
    }
    @Test fun rawtext() = assertEquals("X", formatLogText("X", LogFormat.RAWTEXT, 190, 3))
    @Test fun plain() = assertEquals("<C>●</>X", formatLogText("X", LogFormat.PLAIN, 190, 3))
    @Test fun yearMonth() = assertEquals("<C>●</>190년 3월:X", formatLogText("X", LogFormat.YEAR_MONTH, 190, 3))
    @Test fun year() = assertEquals("<C>●</>190년:X", formatLogText("X", LogFormat.YEAR, 190, 3))
    @Test fun month() = assertEquals("<C>●</>3월:X", formatLogText("X", LogFormat.MONTH, 190, 3))
    @Test fun eventPlain() = assertEquals("<S>◆</>X", formatLogText("X", LogFormat.EVENT_PLAIN, 190, 3))
    @Test fun eventYearMonth() = assertEquals("<S>◆</>190년 3월:X", formatLogText("X", LogFormat.EVENT_YEAR_MONTH, 190, 3))
    @Test fun notice() = assertEquals("<R>★</>X", formatLogText("X", LogFormat.NOTICE, 190, 3))
    @Test fun noticeYearMonth() = assertEquals("<R>★</>190년 3월:X", formatLogText("X", LogFormat.NOTICE_YEAR_MONTH, 190, 3))
}
