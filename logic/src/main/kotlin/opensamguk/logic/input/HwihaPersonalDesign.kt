package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One-phase personal action values. PROPOSED keeps reservation and execution closed. */
data class HwihaPersonalDesign(
    val status: String,
    val experiencePerAction: Int,
    val dedicationPerAction: Int,
    val trainingStatGain: Int,
    val trainingFatigueGain: Int,
    val recuperationInjuryRecovery: Int,
    val recuperationFatigueRecovery: Int,
) {
    init {
        require(status == "PROPOSED" || status == CONFIRMED)
        require(experiencePerAction >= 0 && dedicationPerAction >= 0)
        require(trainingStatGain in 1..10 && trainingFatigueGain in 0..100)
        require(recuperationInjuryRecovery in 1..100 && recuperationFatigueRecovery in 1..100)
    }

    companion object {
        const val CONFIRMED = "CONFIRMED"
        private const val RESOURCE = "hwiha/hwiha-personal-v1.json"
        val CANON by lazy { parse(checkNotNull(HwihaPersonalDesign::class.java.classLoader.getResource(RESOURCE)).readText()) }

        fun parse(payload: String): HwihaPersonalDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.keys == setOf("schemaVersion", "ledgerId", "status", "note", "experiencePerAction",
                "dedicationPerAction", "trainingStatGain", "trainingFatigueGain",
                "recuperationInjuryRecovery", "recuperationFatigueRecovery"))
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1 &&
                root.getValue("ledgerId").jsonPrimitive.content == "hwiha-personal-v1" &&
                root.getValue("note").jsonPrimitive.content.isNotBlank())
            fun number(name: String) = root.getValue(name).jsonPrimitive.int
            return HwihaPersonalDesign(root.getValue("status").jsonPrimitive.content,
                number("experiencePerAction"), number("dedicationPerAction"), number("trainingStatGain"),
                number("trainingFatigueGain"), number("recuperationInjuryRecovery"),
                number("recuperationFatigueRecovery"))
        }
    }
}
