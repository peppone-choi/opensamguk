package opensamguk.logic.battle.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Game numbers are product decisions. The original game's numeric formulas remain UNKNOWN. */
data class TacticalRules(
    val battleTicks: Int,
    val moraleRetreatBelow: Int,
    val initialMoraleBase: Int,
    val strengthMoraleDivisor: Int,
    val retinueMoraleDivisor: Int,
    val infantryMoveTicks: Int,
    val archerMoveTicks: Int,
    val cavalryMoveTicks: Int,
    val forestExtraTicks: Int,
    val riverExtraTicks: Int,
    val attackIntervalTicks: Int,
    val archerRange: Int,
    val baseDamagePercent: Int,
    val cavalryVsArcherPercent: Int,
    val infantryVsCavalryPercent: Int,
    val archerVsInfantryPercent: Int,
    val forestDefensePercent: Int,
    val gateDamagePerAttack: Int,
) {
    companion object {
        private const val RESOURCE = "battle/waryong-tactical-rules-v1.json"
        val CANON: TacticalRules by lazy {
            parse(checkNotNull(TacticalRules::class.java.classLoader.getResource(RESOURCE)).readText())
        }

        fun parse(payload: String): TacticalRules {
            val root = Json.parseToJsonElement(payload).jsonObject
            require(root.int("schemaVersion") == 1)
            require(root.getValue("status").jsonPrimitive.content == "CONFIRMED")
            require(root.getValue("decidedBy").jsonPrimitive.content.isNotBlank())
            val session = root.section("session")
            val morale = root.section("morale")
            val movement = root.section("movement")
            val combat = root.section("combat")
            val siege = root.section("siege")
            require(session.int("tickMillis") == 100 && session.int("joinWaitSeconds") == 60)
            val rules = TacticalRules(
                battleTicks = session.int("battleTicks"),
                moraleRetreatBelow = morale.int("retreatBelow"),
                initialMoraleBase = morale.int("initialBase"),
                strengthMoraleDivisor = morale.int("generalStrengthDivisor"),
                retinueMoraleDivisor = morale.int("bugokMoraleDivisor"),
                infantryMoveTicks = movement.int("infantryTicksPerTile"),
                archerMoveTicks = movement.int("archerTicksPerTile"),
                cavalryMoveTicks = movement.int("cavalryTicksPerTile"),
                forestExtraTicks = movement.int("forestExtraTicks"),
                riverExtraTicks = movement.int("riverExtraTicks"),
                attackIntervalTicks = combat.int("attackIntervalTicks"),
                archerRange = combat.int("archerRangeTiles"),
                baseDamagePercent = combat.int("baseDamagePercent"),
                cavalryVsArcherPercent = combat.int("cavalryVsArcherPercent"),
                infantryVsCavalryPercent = combat.int("infantryVsCavalryPercent"),
                archerVsInfantryPercent = combat.int("archerVsInfantryPercent"),
                forestDefensePercent = combat.int("forestDefensePercent"),
                gateDamagePerAttack = siege.int("gateDamagePerAttack"),
            )
            require(rules.battleTicks > 0 && rules.attackIntervalTicks > 0 && rules.archerRange > 0)
            require(listOf(rules.strengthMoraleDivisor, rules.retinueMoraleDivisor, rules.infantryMoveTicks,
                rules.archerMoveTicks, rules.cavalryMoveTicks).all { it > 0 })
            return rules
        }

        private fun JsonObject.section(key: String): JsonObject = getValue(key).jsonObject
        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    }
}
