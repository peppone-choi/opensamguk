package com.opensam.util

/**
 * Korean postposition (조사) utility.
 * Picks the correct form of a 조사 based on whether the last character of the preceding word
 * has a final consonant (받침).
 */
object JosaUtil {

    private val JOSA_MAP = mapOf(
        "이" to ("이" to "가"),
        "가" to ("이" to "가"),
        "을" to ("을" to "를"),
        "를" to ("을" to "를"),
        "은" to ("은" to "는"),
        "는" to ("은" to "는"),
        "과" to ("과" to "와"),
        "와" to ("과" to "와"),
        "로" to ("으로" to "로"),
        "으로" to ("으로" to "로"),
        "아" to ("아" to "야"),
        "야" to ("아" to "야"),
    )

    /**
     * Pick the correct josa for the given word.
     * @param word The preceding word (Korean text)
     * @param josa The josa key (e.g., "이", "을", "은", "와", "로")
     * @return The correct form of the josa
     */
    fun pick(word: String, josa: String): String {
        val pair = JOSA_MAP[josa] ?: return josa
        val lastChar = word.lastOrNull() ?: return pair.first

        if (lastChar < '\uAC00' || lastChar > '\uD7A3') {
            // Not a Hangul syllable; assume no 받침
            return pair.second
        }

        val code = lastChar.code - 0xAC00
        val jongsung = code % 28

        // Special case for "로": ㄹ받침 uses "로" not "으로"
        if (josa == "로" || josa == "으로") {
            return if (jongsung == 0 || jongsung == 8) pair.second else pair.first
        }

        return if (jongsung == 0) pair.second else pair.first
    }
}
