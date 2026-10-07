package opensamguk.logic.input

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The selected county is explicit; an empty reservation never inherits the current siege. */
object SiegeAssaultInput {
    const val INPUT_ID = "action.assault"

    fun parse(rawJson: String?): Int? {
        if (rawJson == null) return null
        return try {
            val fields = FlatArguments(rawJson).read()
            if (fields.keys != setOf("targetCountyId")) return null
            val target = fields["targetCountyId"] as? JsonPrimitive ?: return null
            if (target.isString) null else target.intOrNull?.takeIf { it > 0 }
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(targetCountyId: Int): String {
        require(targetCountyId > 0)
        return buildJsonObject { put("targetCountyId", targetCountyId) }.toString()
    }
}
