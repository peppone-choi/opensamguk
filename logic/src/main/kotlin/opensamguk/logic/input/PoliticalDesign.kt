package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Rise is approved without a renown threshold; independence remains proposed and closed in the input ledger. */
data class PoliticalDesign(val independenceMinimumRenown: Int,
    val initialDiplomacyState: Int) {
    init {
        require(independenceMinimumRenown >= 0)
        require(initialDiplomacyState == 2)
    }

    companion object {
        val CANON by lazy {
            val resource = checkNotNull(PoliticalDesign::class.java.classLoader
                .getResource("campaign/political-v1.json"))
            val root = Json.parseToJsonElement(resource.readText()).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note",
                "riseStatus", "independenceStatus", "independenceMinimumRenown", "initialDiplomacyState"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            require(root.getValue("ledgerId").jsonPrimitive.content == "political-v1")
            require(root.getValue("status").jsonPrimitive.content == "PARTIALLY_APPROVED")
            require(root.getValue("riseStatus").jsonPrimitive.content == "APPROVED")
            require(root.getValue("independenceStatus").jsonPrimitive.content == "PROPOSED")
            PoliticalDesign(root.getValue("independenceMinimumRenown").jsonPrimitive.int,
                root.getValue("initialDiplomacyState").jsonPrimitive.int)
        }
    }
}
