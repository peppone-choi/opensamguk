package opensamguk.logic.creation

import java.text.Normalizer
import java.util.Locale

/** The confirmed creation name rule; length is Unicode code points after NFC and trim. */
data class CreationNameRule(val maxCodePoints: Int) {
    init { require(maxCodePoints in 1..100) }

    fun normalize(raw: String): String? {
        // A control character is never silently erased by trim (including a leading newline/tab).
        if (raw.codePoints().anyMatch { Character.getType(it) == Character.CONTROL.toInt() }) return null
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        val codePoints = normalized.codePoints().toArray()
        if (codePoints.isEmpty() || codePoints.size > maxCodePoints) return null
        if (!approvedLetter(codePoints.first()) || !approvedLetter(codePoints.last())) return null
        for (index in codePoints.indices) {
            val codePoint = codePoints[index]
            if (approvedLetter(codePoint)) continue
            if ((codePoint == ' '.code || codePoint == '·'.code) && index > 0 && index < codePoints.lastIndex &&
                approvedLetter(codePoints[index - 1]) && approvedLetter(codePoints[index + 1])) continue
            return null
        }
        return normalized
    }

    /** Java's full-string upper then lower catches common multi-character folds such as ß→ss. */
    fun uniqueKey(raw: String): String? = normalize(raw)?.let(::caseFold)

    /** Existing scenario names may use characters not accepted for new custom input. */
    fun collisionKeyExisting(raw: String): String = caseFold(
        stripLegacyNpcMarker(Normalizer.normalize(raw, Normalizer.Form.NFC).trim()))

    companion object {
        const val MAX_CODE_POINTS = 12
        val APPROVED = CreationNameRule(MAX_CODE_POINTS)

        private fun approvedLetter(codePoint: Int): Boolean = Character.isLetter(codePoint) &&
            Character.UnicodeScript.of(codePoint) in setOf(
                Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.LATIN,
            )

        private fun caseFold(value: String): String = Normalizer.normalize(
            value.uppercase(Locale.ROOT).lowercase(Locale.ROOT), Normalizer.Form.NFC)

        /** ScenarioImporter prefixes active NPC names for legacy display; the marker is not part of a person's name. */
        fun stripLegacyNpcMarker(raw: String): String = raw.dropWhile { it in setOf('ⓝ', 'ⓜ', 'ⓖ', '㉥', 'ⓤ', 'ⓧ') }
    }
}
