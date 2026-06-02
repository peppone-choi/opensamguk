package opensamguk.logic.util

/**
 * Faithful port of the parts of PHP `sammo\StringUtil` the intake commands rely on.
 *
 * GRAND TRUTH: `legacy/devsam-core/src/sammo/StringUtil.php`. PHP wins every divergence.
 *
 * Only the arities the ported commands actually call are implemented (faithful-port rule 5: port
 * what is used, never fabricate the rest). The troop commands (NewTroop / SetTroopName) call
 * `neutralize($str)` with no `$maxLen`, so the width-truncation branch (which would need
 * `subStringForWidth`) is intentionally out of scope here.
 */
object StringUtil {

    /**
     * `StringUtil::neutralize(?string $str)` — `StringUtil.php:156-166` (no-`$maxLen` arity).
     *
     * ```php
     * if(!$str){ return ''; }
     * $str = htmlspecialchars($str);
     * $str = StringUtil::textStrip($str);
     * return $str;
     * ```
     *
     * PHP truthiness: `!$str` is true for `null` AND `""` (and the string `"0"` — but a 1-18 width
     * validator already rejects empties upstream; `"0"` collapses to `''` exactly as PHP does).
     * Order is load-bearing: htmlspecialchars FIRST, then strip — a leading/trailing space survives
     * the escape (space is not escaped) and is then trimmed.
     */
    fun neutralize(str: String?): String {
        // PHP `!$str`: null, "", and "0" are all falsy → ''.
        if (str.isNullOrEmpty() || str == "0") return ""
        return textStrip(htmlspecialchars(str))
    }

    /**
     * `StringUtil::textStrip(?string $str)` — `StringUtil.php:168-173`.
     *
     * `preg_replace('/^[\pZ\pC]+|[\pZ\pC]+$/u','',$str)` — strip a run of leading/trailing Unicode
     * Separator (`\pZ` = Zs/Zl/Zp) or Other/control (`\pC` = Cc/Cf/Cs/Co/Cn) code points. Java's
     * one-letter general-category classes `\p{Z}`/`\p{C}` are the exact equivalents; control chars
     * such as `\t`/`\n` are `Cc` (in `\pC`), so they are trimmed too.
     */
    fun textStrip(str: String?): String {
        if (str.isNullOrEmpty() || str == "0") return ""
        return STRIP_EDGES.replace(str, "")
    }

    private val STRIP_EDGES = Regex("^[\\p{Z}\\p{C}]+|[\\p{Z}\\p{C}]+$")

    /**
     * PHP `mb_strwidth($str)` — cumulative DISPLAY width. East-Asian Wide/Fullwidth code points
     * (Hangul / CJK / Enclosed-CJK / Fullwidth forms, UAX #11 W + F) count as width 2; everything
     * else width 1. Backs the `Validator::stringWidthBetween` rule the troop commands declare.
     *
     * NOTE: [isEastAsianWide] is a deliberate sibling of the private predicate in
     * `actions/founding/CheGeobyeong.kt` (`mbStrimwidth`). The two are NOT consolidated here to avoid
     * co-widening a founding-family file from a troop slice; a later refactor may fold them together.
     */
    fun mbStrwidth(str: String): Int {
        var width = 0
        var i = 0
        while (i < str.length) {
            val cp = str.codePointAt(i)
            width += if (isEastAsianWide(cp)) 2 else 1
            i += Character.charCount(cp)
        }
        return width
    }

    private fun isEastAsianWide(cp: Int): Boolean = when (cp) {
        in 0x1100..0x115F,      // Hangul Jamo
        in 0x2E80..0x303E,      // CJK Radicals … Kangxi … CJK Symbols
        in 0x3041..0x33FF,      // Hiragana/Katakana/CJK punct/Enclosed-CJK/CJK compat
        in 0x3400..0x4DBF,      // CJK Ext A
        in 0x4E00..0x9FFF,      // CJK Unified
        in 0xA000..0xA4CF,      // Yi
        in 0xAC00..0xD7A3,      // Hangul syllables
        in 0xF900..0xFAFF,      // CJK Compatibility Ideographs
        in 0xFE30..0xFE4F,      // CJK Compatibility Forms
        in 0xFF00..0xFF60,      // Fullwidth Forms
        in 0xFFE0..0xFFE6,      // Fullwidth signs
        in 0x20000..0x3FFFD,    // CJK Ext B+ (supplementary)
        -> true
        else -> false
    }

    /**
     * `htmlspecialchars($str)` with PHP 8.1+ default flags `ENT_QUOTES | ENT_SUBSTITUTE |
     * ENT_HTML401` — the encoding target is PHP 8.3 (`tools/php-golden`). `&` MUST be replaced first
     * (else the later entities double-encode). Single quote → `&#039;` (numeric, ENT_QUOTES);
     * double quote → `&quot;`. `ENT_SUBSTITUTE` only affects malformed UTF-8 (absent for valid
     * input), so it needs no special handling here.
     */
    private fun htmlspecialchars(str: String): String {
        val sb = StringBuilder(str.length)
        for (ch in str) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#039;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
