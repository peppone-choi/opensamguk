package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One-phase direct military rates from the curated Han design ledger. */
data class HwihaMilitaryDesign(
    val status: String,
    val conscriptHouseholdPermille: Int,
    val volunteerHouseholdPermille: Int,
    val grainPerTroop: Long,
    val moneyPerVolunteer: Long,
    val trainingGain: Int,
    val moraleGain: Int,
    val demobilizeTroopPermille: Int,
    val experience: Int,
    val dedication: Int,
) {
    init {
        require(status == "PROPOSED" || status == CONFIRMED)
        require(conscriptHouseholdPermille in 1..1000 && volunteerHouseholdPermille in 1..1000)
        require(grainPerTroop >= 0 && moneyPerVolunteer >= 0)
        require(trainingGain in 1..100 && moraleGain in 1..100)
        require(demobilizeTroopPermille in 1..1000 && experience >= 0 && dedication >= 0)
    }

    companion object {
        const val RESOURCE = "hwiha/hwiha-military-v1.json"
        const val CONFIRMED = "CONFIRMED"
        val CANON by lazy { parse(checkNotNull(HwihaMilitaryDesign::class.java.classLoader.getResource(RESOURCE)).readText()) }

        fun parse(payload: String): HwihaMilitaryDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note", "conscriptHouseholdPermille",
                "volunteerHouseholdPermille", "grainPerTroop", "moneyPerVolunteer", "trainingGain", "moraleGain",
                "demobilizeTroopPermille", "experience", "dedication"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1 &&
                root.getValue("ledgerId").jsonPrimitive.content == "hwiha-military-v1")
            require(root.getValue("note").jsonPrimitive.content.isNotBlank())
            fun count(name: String) = root.getValue(name).jsonPrimitive.int
            fun resource(name: String) = root.getValue(name).jsonPrimitive.long
            return HwihaMilitaryDesign(root.getValue("status").jsonPrimitive.content,
                count("conscriptHouseholdPermille"), count("volunteerHouseholdPermille"),
                resource("grainPerTroop"), resource("moneyPerVolunteer"), count("trainingGain"), count("moraleGain"),
                count("demobilizeTroopPermille"), count("experience"), count("dedication"))
        }
    }
}
