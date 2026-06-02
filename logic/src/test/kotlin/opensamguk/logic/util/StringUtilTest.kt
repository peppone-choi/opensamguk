package opensamguk.logic.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parity oracle = PHP `StringUtil::neutralize`/`textStrip` semantics (`StringUtil.php:156-173`)
 * evaluated by hand against PHP 8.3 `htmlspecialchars` defaults (ENT_QUOTES | ENT_HTML401).
 */
class StringUtilTest {

    @Test
    fun `neutralize passes a clean name through unchanged`() {
        assertEquals("부대", StringUtil.neutralize("부대"))
        assertEquals("제1군단", StringUtil.neutralize("제1군단"))
    }

    @Test
    fun `neutralize falsy inputs collapse to empty (PHP !str)`() {
        assertEquals("", StringUtil.neutralize(null))
        assertEquals("", StringUtil.neutralize(""))
        assertEquals("", StringUtil.neutralize("0")) // PHP "0" is falsy
    }

    @Test
    fun `neutralize html-escapes before trimming (ENT_QUOTES defaults)`() {
        assertEquals("&lt;b&gt;", StringUtil.neutralize("<b>"))
        assertEquals("a&amp;b", StringUtil.neutralize("a&b"))
        assertEquals("a&quot;b&#039;c", StringUtil.neutralize("a\"b'c"))
        // ampersand replaced first → no double-encoding of the following entity chars.
        assertEquals("&amp;lt;", StringUtil.neutralize("&lt;"))
    }

    @Test
    fun `neutralize strips leading and trailing unicode separators and controls`() {
        assertEquals("부대", StringUtil.neutralize("  부대  "))
        assertEquals("부대", StringUtil.neutralize("\t부대\n"))
        // htmlspecialchars runs first; the surrounding ASCII spaces survive the escape then trim.
        assertEquals("&lt;b&gt;", StringUtil.neutralize(" <b> "))
        // interior whitespace is preserved — only the edges are stripped.
        assertEquals("제 1 군", StringUtil.neutralize("  제 1 군  "))
    }

    @Test
    fun `textStrip trims edges only and leaves interior intact`() {
        assertEquals("a b", StringUtil.textStrip("  a b  "))
        assertEquals("", StringUtil.textStrip("   "))
        assertEquals("", StringUtil.textStrip(null))
    }
}
