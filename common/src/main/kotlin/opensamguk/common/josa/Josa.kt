package opensamguk.common.josa

internal object JosaTables {
    val DEFAULT_POSTPOSITION: Map<String, String> = linkedMapOf(
        "은" to "는", "이" to "가", "과" to "와", "이나" to "나",
        "을" to "를", "으로" to "로", "이라" to "라", "이랑" to "랑",
    )
    val MAP_POSTPOSITION: Map<String, String> = buildMap {
        for ((key, value) in DEFAULT_POSTPOSITION) { put(key, key); put(value, key); put("($key)$value", key) }
    }
}

internal object JosaDetect {
    private const val KO_START_CODE = 0xAC00
    private const val KO_FINISH_CODE = 0xD7A3
    private const val JONGSUNG_RIEUL = 8
    private val REG_INVALID_CHAR = Regex("[^a-zA-Z0-9\\u3131-\\u314E\\uAC00-\\uD7A3\\u4E00-\\u5ED3\\s]+")
    private val REG_TARGET_CHAR = Regex("^[\\s\\S]*?(\\S*)\\s*$")

    fun getLastChar(text: String): String {
        var cleaned = REG_INVALID_CHAR.replace(text, " ")
        cleaned = REG_TARGET_CHAR.replace(cleaned) { m -> m.groupValues[1] }
        cleaned = cleaned.trim()
        if (cleaned.isEmpty()) return ""
        val cps = cleaned.codePoints().toArray()
        if (cps.isEmpty()) return ""
        return String(Character.toChars(cps[cps.size - 1]))
    }
    private data class DigitJong(val has: Boolean, val rieul: Boolean)
    private fun getDigitJongsung(digit: Int): DigitJong = when (digit) {
        0, 3, 6 -> DigitJong(true, false); 1, 7, 8 -> DigitJong(true, true); else -> DigitJong(false, false)
    }
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')

    fun hasJongsung(text: String, isRo: Boolean): Boolean {
        val lastChar = getLastChar(text)
        if (lastChar.isEmpty()) return false
        val code = lastChar.codePointAt(0)
        if (code in KO_START_CODE..KO_FINISH_CODE) {
            val jongsung = (code - KO_START_CODE) % 28
            if (jongsung == 0) return false
            if (isRo && jongsung == JONGSUNG_RIEUL) return false
            return true
        }
        if (lastChar.length == 1 && lastChar[0] in 'ㄱ'..'ㅎ') { if (isRo && lastChar[0] == 'ㄹ') return false; return true }
        if (lastChar.length == 1 && lastChar[0] in '0'..'9') {
            val (has, rieul) = getDigitJongsung(lastChar[0].digitToInt()); if (isRo && rieul) return false; return has
        }
        val lower = lastChar.lowercase()
        return !(lower.length == 1 && lower[0] in VOWELS)
    }
}
