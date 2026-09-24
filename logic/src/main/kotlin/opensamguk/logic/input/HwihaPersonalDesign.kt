package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One-phase personal action values. PROPOSED keeps reservation and execution closed. */
data class HwihaPersonalDesign(
    val status: String,
    val travelExperience: Int,
    val travelDedication: Int,
    val trainingStatGain: Int,
    val trainingStatCap: Int,
    val trainingFatigueGain: Int,
    val recuperationInjuryRecovery: Int,
    val recuperationFatigueRecovery: Int,
    val retirementMinimumAge: Int,
) {
    init {
        require(status == "PROPOSED" || status == CONFIRMED)
        require(travelExperience >= 0 && travelDedication >= 0)
        require(trainingStatGain in 1..10 && trainingFatigueGain in 0..100)
        require(trainingStatCap in 1..100 && retirementMinimumAge in 1..120)
        require(recuperationInjuryRecovery in 1..100 && recuperationFatigueRecovery in 1..100)
    }

    companion object {
        const val CONFIRMED = "CONFIRMED"
        private const val RESOURCE = "hwiha/hwiha-personal-v1.json"
        val CANON by lazy { parse(checkNotNull(HwihaPersonalDesign::class.java.classLoader.getResource(RESOURCE)).readText()) }

        fun parse(payload: String): HwihaPersonalDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note", "travelExperience",
                "travelDedication", "trainingStatGain", "trainingStatCap", "trainingFatigueGain",
                "recuperationInjuryRecovery", "recuperationFatigueRecovery", "retirementMinimumAge"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1 &&
                root.getValue("ledgerId").jsonPrimitive.content == "hwiha-personal-v1" &&
                root.getValue("note").jsonPrimitive.content.isNotBlank())
            fun number(name: String) = root.getValue(name).jsonPrimitive.int
            return HwihaPersonalDesign(root.getValue("status").jsonPrimitive.content,
                number("travelExperience"), number("travelDedication"), number("trainingStatGain"),
                number("trainingStatCap"), number("trainingFatigueGain"), number("recuperationInjuryRecovery"),
                number("recuperationFatigueRecovery"), number("retirementMinimumAge"))
        }
    }
}
