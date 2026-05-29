package opensamguk.common.josa
import kotlin.test.Test; import kotlin.test.assertEquals
class JosaMapTest {
    @Test fun `default postposition has 8 pairs`() {
        assertEquals(8, JosaTables.DEFAULT_POSTPOSITION.size)
        assertEquals("는", JosaTables.DEFAULT_POSTPOSITION["은"]); assertEquals("로", JosaTables.DEFAULT_POSTPOSITION["으로"])
    }
    @Test fun `map registers key value and parenthesized forms`() {
        assertEquals(24, JosaTables.MAP_POSTPOSITION.size)
        assertEquals("은", JosaTables.MAP_POSTPOSITION["은"]); assertEquals("은", JosaTables.MAP_POSTPOSITION["는"])
        assertEquals("은", JosaTables.MAP_POSTPOSITION["(은)는"]); assertEquals("으로", JosaTables.MAP_POSTPOSITION["(으로)로"])
    }
}
