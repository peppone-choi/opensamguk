package opensamguk.common.log
import kotlin.test.Test; import kotlin.test.assertEquals; import kotlin.test.assertTrue
class ActionLoggerTest {
    @Test fun `flush drains and clears`() { val l = ActionLogger(5); l.pushGeneralActionLog("a"); l.pushGeneralActionLog("b"); assertEquals(2, l.flush().size); assertTrue(l.flush().isEmpty()) }
    @Test fun `rollback identical to flush`() { val l = ActionLogger(5); l.pushGeneralActionLog("a"); assertEquals(1, l.rollback().size); assertTrue(l.flush().isEmpty()) }
    @Test fun `array push skips empty strings`() { val l = ActionLogger(5); l.pushGeneralActionLog(listOf("a","","b")); assertEquals(2, l.flush().size) }
    @Test fun `scalar empty string skipped`() { val l = ActionLogger(5); l.pushGeneralActionLog(""); assertTrue(l.flush().isEmpty()) }
    @Test fun `general draft attaches id zero`() { val l = ActionLogger(0); l.pushGeneralActionLog("x"); assertEquals(0, l.flush()[0].generalId) }
    @Test fun `general draft null id when not provided`() { val l = ActionLogger(); l.pushGeneralActionLog("x"); assertEquals(null, l.flush()[0].generalId) }
    @Test fun `nation history drops nationId zero`() { val l = ActionLogger(nationId = 0); l.pushNationHistoryLog("x"); assertTrue(l.flush().isEmpty()) }
    @Test fun `nation history keeps nonzero nationId`() { val l = ActionLogger(nationId = 3); l.pushNationHistoryLog("x"); assertEquals(3, l.flush()[0].nationId) }
    @Test fun `global action log has system scope and summary category`() {
        val l = ActionLogger(); l.pushGlobalActionLog("x"); val d = l.flush()[0]
        assertEquals(LogScope.SYSTEM, d.scope); assertEquals(LogCategory.SUMMARY, d.category); assertEquals(LogFormat.MONTH, d.format)
    }
}
