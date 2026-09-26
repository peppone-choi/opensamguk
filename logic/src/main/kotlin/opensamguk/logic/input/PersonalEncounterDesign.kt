package opensamguk.logic.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Versioned source of the implemented personal encounter numbers. */
data class PersonalEncounterDesign(
    val strengthWeight: Int, val leadershipWeight: Int, val moraleDivisor: Int,
    val fatigueDivisor: Int, val injuryWeight: Int, val powerFloor: Int,
    val attackDivisor: Int, val defenseDivisor: Int, val defenderAdvantage: Int, val damageFloor: Int,
    val roundLimit: Int, val captureMoraleAtMost: Int, val captureInjuryAtLeast: Int,
    val attackerInjuryDivisor: Int, val defenderInjuryDivisor: Int,
    val fatiguePerRound: Int, val moraleOnWin: Int, val moraleLossPerRound: Int,
    val restFatigueRecovery: Int, val restMoraleRecovery: Int,
) {
    companion object {
        const val RESOURCE = "campaign/personal-encounter-v1.json"
        val CANON: PersonalEncounterDesign by lazy {
            parse(checkNotNull(PersonalEncounterDesign::class.java.classLoader.getResource(RESOURCE)).readText())
        }

        fun parse(payload: String): PersonalEncounterDesign {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.keys == setOf("schemaVersion", "status", "source", "power", "damage", "roundLimit",
                "capture", "injury", "condition"))
            require(root.number("schemaVersion") == 1)
            require(root.getValue("status").jsonPrimitive.content == "IMPLEMENTED_PROVISIONAL")
            require(root.getValue("source").jsonPrimitive.content.isNotBlank())
            val power = root.getValue("power").jsonObject.exact("strengthWeight", "leadershipWeight", "moraleDivisor",
                "fatigueDivisor", "injuryWeight", "floor")
            val damage = root.getValue("damage").jsonObject.exact("attackDivisor", "defenseDivisor", "defenderAdvantage", "floor")
            val capture = root.getValue("capture").jsonObject.exact("moraleAtMost", "injuryAtLeast")
            val injury = root.getValue("injury").jsonObject.exact("attackerDivisor", "defenderDivisor")
            val condition = root.getValue("condition").jsonObject.exact("fatiguePerRound", "moraleOnWin",
                "moraleLossPerRound", "restFatigueRecovery", "restMoraleRecovery")
            return PersonalEncounterDesign(power.number("strengthWeight"), power.number("leadershipWeight"),
                power.number("moraleDivisor"), power.number("fatigueDivisor"), power.number("injuryWeight"), power.number("floor"),
                damage.number("attackDivisor"), damage.number("defenseDivisor"), damage.number("defenderAdvantage"),
                damage.number("floor"), root.number("roundLimit"), capture.number("moraleAtMost"),
                capture.number("injuryAtLeast"), injury.number("attackerDivisor"), injury.number("defenderDivisor"),
                condition.number("fatiguePerRound"), condition.number("moraleOnWin"), condition.number("moraleLossPerRound"),
                condition.number("restFatigueRecovery"), condition.number("restMoraleRecovery")).also { design ->
                require(listOf(design.moraleDivisor, design.fatigueDivisor, design.attackDivisor,
                    design.defenseDivisor, design.attackerInjuryDivisor, design.defenderInjuryDivisor, design.roundLimit)
                    .all { it > 0 })
                require(design.captureMoraleAtMost in 0..100 && design.captureInjuryAtLeast in 0..100)
            }
        }

        private fun JsonObject.exact(vararg keys: String): JsonObject = also { require(it.keys == keys.toSet()) }
        private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.let {
            require(!it.isString) { "$key must be a number" }
            it.int
        }
    }
}
