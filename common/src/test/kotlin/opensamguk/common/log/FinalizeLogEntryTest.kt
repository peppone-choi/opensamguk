package opensamguk.common.log
import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertNull
class FinalizeLogEntryTest {
    private val ctx = LogContext(190, 3)
    @Test fun `general scope with id zero is dropped`() = assertNull(finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "x", generalId = 0), ctx))
    @Test fun `general scope with null id is dropped`() = assertNull(finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "x", generalId = null), ctx))
    @Test fun `system scope never dropped`() = assertEquals("x", finalizeLogEntry(LogEntryDraft(LogScope.SYSTEM, LogCategory.SUMMARY, "x"), ctx)!!.text)
    @Test fun `bakes year_month prefix`() {
        val r = finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "투자", generalId = 7, format = LogFormat.YEAR_MONTH), ctx)!!
        assertEquals("<C>●</>190년 3월:투자", r.text); assertEquals(7, r.generalId); assertEquals(190, r.year)
    }
    @Test fun `default format is rawtext when format null`() = assertEquals("투자", finalizeLogEntry(LogEntryDraft(LogScope.GENERAL, LogCategory.ACTION, "투자", generalId = 7, format = null), ctx)!!.text)
}
