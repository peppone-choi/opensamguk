package opensamguk.logic.battle.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Game numbers are product decisions. The original game's numeric formulas remain UNKNOWN. */
data class TacticalRules(
    val battleTicks: Int,
    val moraleScaleMax: Int,
    val topMapCandidates: Int,
    val moraleRetreatBelow: Int,
    val initialMoraleBase: Int,
    val strengthMoraleDivisor: Int,
    val retinueMoraleDivisor: Int,
    val moraleLossPerCasualtyPercent: Int,
    val infantryMoveTicks: Int,
    val archerMoveTicks: Int,
    val cavalryMoveTicks: Int,
    val forestExtraTicks: Int,
    val riverExtraTicks: Int,
    val attackIntervalTicks: Int,
    val infantryRange: Int,
    val archerRange: Int,
    val cavalryRange: Int,
    val baseDamagePercent: Int,
    val minimumDamage: Int,
    val cavalryVsArcherPercent: Int,
    val infantryVsCavalryPercent: Int,
    val archerVsInfantryPercent: Int,
    val forestDefensePercent: Int,
    val riverDefensePercent: Int,
    val supplyShortageDamagePercent: Int,
    val gateDamagePerAttack: Int,
    val duelOfferMinimumStrength: Int,
    val duelAiAcceptDifference: Int,
    val duelRoundLimit: Int,
    val duelStrengthWeight: Int,
    val duelLeadershipWeight: Int,
    val duelDamageDivisor: Int,
    val duelMinimumDamage: Int,
    val duelRollRange: Int,
    val duelWinnerMoraleBonus: Int,
    val duelLoserMoralePenalty: Int,
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
            val mapSelection = root.section("mapSelection")
            val duel = root.section("duel")
            require(session.int("tickMillis") == 100 && session.int("joinWaitSeconds") == 60)
            val rules = TacticalRules(
                battleTicks = session.int("battleTicks"),
                moraleScaleMax = morale.int("scaleMax"),
                topMapCandidates = mapSelection.int("topCandidates"),
                moraleRetreatBelow = morale.int("retreatBelow"),
                initialMoraleBase = morale.int("initialBase"),
                strengthMoraleDivisor = morale.int("generalStrengthDivisor"),
                retinueMoraleDivisor = morale.int("bugokMoraleDivisor"),
                moraleLossPerCasualtyPercent = morale.int("lossPerCasualtyPercent"),
                infantryMoveTicks = movement.int("infantryTicksPerTile"),
                archerMoveTicks = movement.int("archerTicksPerTile"),
                cavalryMoveTicks = movement.int("cavalryTicksPerTile"),
                forestExtraTicks = movement.int("forestExtraTicks"),
                riverExtraTicks = movement.int("riverExtraTicks"),
                attackIntervalTicks = combat.int("attackIntervalTicks"),
                infantryRange = combat.int("infantryRangeTiles"),
                archerRange = combat.int("archerRangeTiles"),
                cavalryRange = combat.int("cavalryRangeTiles"),
                baseDamagePercent = combat.int("baseDamagePercent"),
                minimumDamage = combat.int("minimumDamage"),
                cavalryVsArcherPercent = combat.int("cavalryVsArcherPercent"),
                infantryVsCavalryPercent = combat.int("infantryVsCavalryPercent"),
                archerVsInfantryPercent = combat.int("archerVsInfantryPercent"),
                forestDefensePercent = combat.int("forestDefensePercent"),
                riverDefensePercent = combat.int("riverDefensePercent"),
                supplyShortageDamagePercent = combat.int("supplyShortageDamagePercent"),
                gateDamagePerAttack = siege.int("gateDamagePerAttack"),
                duelOfferMinimumStrength = duel.int("offerMinimumStrength"),
                duelAiAcceptDifference = duel.int("aiAcceptStrengthDifferenceAtMost"),
                duelRoundLimit = duel.int("roundLimit"),
                duelStrengthWeight = duel.int("powerStrengthWeight"),
                duelLeadershipWeight = duel.int("powerLeadershipWeight"),
                duelDamageDivisor = duel.int("damageDivisor"),
                duelMinimumDamage = duel.int("minimumDamage"),
                duelRollRange = duel.int("rollRange"),
                duelWinnerMoraleBonus = morale.int("duelWinnerBonus"),
                duelLoserMoralePenalty = morale.int("duelLoserPenalty"),
            )
            require(rules.battleTicks > 0 && rules.attackIntervalTicks > 0 &&
                rules.topMapCandidates > 0 && rules.moraleScaleMax > 0 && rules.moraleLossPerCasualtyPercent >= 0 &&
                rules.minimumDamage > 0 && listOf(rules.infantryRange, rules.archerRange,
                    rules.cavalryRange).all { it > 0 })
            require(listOf(rules.strengthMoraleDivisor, rules.retinueMoraleDivisor, rules.infantryMoveTicks,
                rules.archerMoveTicks, rules.cavalryMoveTicks).all { it > 0 })
            require(rules.duelOfferMinimumStrength in 0..100 && rules.duelAiAcceptDifference in 0..100)
            require(rules.duelRoundLimit > 0 && rules.duelDamageDivisor > 0 && rules.duelMinimumDamage > 0)
            require(rules.duelStrengthWeight >= 0 && rules.duelLeadershipWeight >= 0 && rules.duelRollRange in 1..256)
            return rules
        }

        private fun JsonObject.section(key: String): JsonObject = getValue(key).jsonObject
        private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int
    }
}
