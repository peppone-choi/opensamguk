package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Proposed one-phase values; delivery remains closed until status is CONFIRMED. */
data class PeopleDesign(
    val status: String,
    val searchDiscoverCount: Int,
    val employBasePercent: Int,
    val captiveBasePercent: Int,
    val minimumPercent: Int,
    val maximumPercent: Int,
    val charmDifferencePercentPerPoint: Int,
    val experience: Int,
    val dedication: Int,
) {
    init {
        require(status == "PROPOSED" || status == CONFIRMED)
        require(searchDiscoverCount in 1..10 && minimumPercent in 0..maximumPercent && maximumPercent <= 100)
        require(employBasePercent in 0..100 && captiveBasePercent in 0..100)
        require(charmDifferencePercentPerPoint in 0..10 && experience >= 0 && dedication >= 0)
    }

    fun acceptancePercent(actorCharm: Int, targetCharm: Int, captive: Boolean): Int {
        require(actorCharm in 0..100 && targetCharm in 0..100)
        val base = if (captive) captiveBasePercent else employBasePercent
        return (base + (actorCharm - targetCharm) * charmDifferencePercentPerPoint)
            .coerceIn(minimumPercent, maximumPercent)
    }

    companion object {
        const val CONFIRMED = "CONFIRMED"
        private const val RESOURCE = "campaign/people-v1.json"
        val CANON by lazy { parse(checkNotNull(PeopleDesign::class.java.classLoader.getResource(RESOURCE)).readText()) }

        fun parse(payload: String): PeopleDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note", "searchDiscoverCount",
                "employBasePercent", "captiveBasePercent", "minimumPercent", "maximumPercent",
                "charmDifferencePercentPerPoint", "experience", "dedication"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1 &&
                root.getValue("ledgerId").jsonPrimitive.content == "people-v1" &&
                root.getValue("note").jsonPrimitive.content.isNotBlank())
            fun number(name: String) = root.getValue(name).jsonPrimitive.int
            return PeopleDesign(root.getValue("status").jsonPrimitive.content,
                number("searchDiscoverCount"), number("employBasePercent"), number("captiveBasePercent"),
                number("minimumPercent"), number("maximumPercent"), number("charmDifferencePercentPerPoint"),
                number("experience"), number("dedication"))
        }
    }
}
