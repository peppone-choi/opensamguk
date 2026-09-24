package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Proposed rules for nation changing actions; the input ledger keeps those actions closed. */
data class HwihaPoliticalDesign(val riseMinimumRenown: Int, val independenceMinimumRenown: Int,
    val initialDiplomacyState: Int) {
    init {
        require(riseMinimumRenown >= 0 && independenceMinimumRenown >= 0)
        require(initialDiplomacyState == 2)
    }

    companion object {
        val CANON by lazy {
            val resource = checkNotNull(HwihaPoliticalDesign::class.java.classLoader
                .getResource("hwiha/hwiha-political-v1.json"))
            val root = Json.parseToJsonElement(resource.readText()).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note",
                "riseMinimumRenown", "independenceMinimumRenown", "initialDiplomacyState"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            require(root.getValue("ledgerId").jsonPrimitive.content == "hwiha-political-v1")
            require(root.getValue("status").jsonPrimitive.content == "PROPOSED")
            HwihaPoliticalDesign(root.getValue("riseMinimumRenown").jsonPrimitive.int,
                root.getValue("independenceMinimumRenown").jsonPrimitive.int,
                root.getValue("initialDiplomacyState").jsonPrimitive.int)
        }
    }
}
