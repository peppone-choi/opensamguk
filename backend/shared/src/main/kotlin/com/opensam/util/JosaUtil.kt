package com.opensam.util

/**
 * Korean 조사 (particle) utility - picks the correct particle variant
 * based on whether the preceding Korean character has a final consonant (받침).
 *
 * Ported from legacy PHP JosaUtil and core2026 TS JosaUtil.
 */
object JosaUtil {
    private val JOSA_MAP = mapOf(
        "이" to ("이" to "가"),
        "가" to ("이" to "가"),
        "을" to ("을" to "를"),
        "를" to ("을" to "를"),
        "은" to ("은" to "는"),
        "는" to ("은" to "는"),
        "와" to ("과" to "와"),
        "과" to ("과" to "와"),
        "로" to ("으로" to "로"),
        "으로" to ("으로" to "로"),
        "아" to ("아" to "야"),
        "야" to ("아" to "야"),
    )

    /**
     * Pick the correct 조사 for the given text.
     * @param text The preceding text (last character is checked for 받침)
     * @param josa The base 조사 form (e.g., "이", "을", "로")
     * @return The correct 조사 variant
     */
    fun pick(text: String, josa: String): String {
        val pair = JOSA_MAP[josa] ?: return josa
        if (text.isEmpty()) return pair.first

        val lastChar = text.last()
        if (lastChar.code < 0xAC00 || lastChar.code > 0xD7A3) {
            // Not a Korean syllable - default to 받침 present
            return pair.first
        }

        val jongseong = (lastChar.code - 0xAC00) % 28

        // Special case for "로": ㄹ받침(8) uses "로" not "으로"
        if (josa == "로" || josa == "으로") {
            return if (jongseong == 0 || jongseong == 8) pair.second else pair.first
        }

        return if (jongseong == 0) pair.second else pair.first
    }
}
