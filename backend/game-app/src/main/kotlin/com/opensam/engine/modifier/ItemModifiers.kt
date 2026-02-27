package com.opensam.engine.modifier

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.opensam.model.ArmType
import com.opensam.model.CrewType
import com.opensam.engine.war.BattleTrigger
import com.opensam.engine.war.BattleTriggerRegistry
import kotlin.math.floor

object ItemModifiers {

    private val items: Map<String, ActionModifier>
    private val itemMeta: Map<String, ItemMeta>
    private val itemTriggerTypes: Map<String, String>

    init {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val stream = ItemModifiers::class.java.classLoader.getResourceAsStream("data/items.json")
            ?: error("items.json not found on classpath")
        val data: Map<String, List<Map<String, Any>>> = mapper.readValue(
            stream, object : TypeReference<Map<String, List<Map<String, Any>>>>() {}
        )

        val resultItems = mutableMapOf<String, ActionModifier>()
        val resultMeta = mutableMapOf<String, ItemMeta>()
        val resultTriggerTypes = mutableMapOf<String, String>()

        // Weapons: strength = grade
        for (item in data["weapons"] ?: emptyList()) {
            val code = item["code"] as String
            val rawName = item["rawName"] as String
            val grade = (item["grade"] as Number).toInt()
            val name = "$rawName(+$grade)"
            resultItems[code] = StatItem(code, name, strength = grade.toDouble())
            resultMeta[code] = ItemMeta(code, rawName, "weapon", grade,
                (item["cost"] as Number).toInt(), item["buyable"] as Boolean,
                (item["rarity"] as Number).toInt())
        }

        // Books: intel = grade
        for (item in data["books"] ?: emptyList()) {
            val code = item["code"] as String
            val rawName = item["rawName"] as String
            val grade = (item["grade"] as Number).toInt()
            val name = "$rawName(+$grade)"
            resultItems[code] = StatItem(code, name, intel = grade.toDouble())
            resultMeta[code] = ItemMeta(code, rawName, "book", grade,
                (item["cost"] as Number).toInt(), item["buyable"] as Boolean,
                (item["rarity"] as Number).toInt())
        }

        // Horses: leadership = grade
        for (item in data["horses"] ?: emptyList()) {
            val code = item["code"] as String
            val rawName = item["rawName"] as String
            val grade = (item["grade"] as Number).toInt()
            val name = "$rawName(+$grade)"
            resultItems[code] = StatItem(code, name, leadership = grade.toDouble())
            resultMeta[code] = ItemMeta(code, rawName, "horse", grade,
                (item["cost"] as Number).toInt(), item["buyable"] as Boolean,
                (item["rarity"] as Number).toInt())
        }

        // Misc items
        for (item in data["misc"] ?: emptyList()) {
            val code = item["code"] as String
            val rawName = item["rawName"] as String
            val consumable = item["consumable"] as? Boolean ?: false
            val cost = (item["cost"] as Number).toInt()
            val buyable = item["buyable"] as? Boolean ?: false
            val rarity = (item["rarity"] as? Number)?.toInt() ?: 0
            val info = item["info"] as? String ?: ""
            val triggerType = item["triggerType"] as? String

            if (triggerType != null) {
                resultTriggerTypes[code] = triggerType
            }

            if (consumable) {
                resultItems[code] = ConsumableItem(
                    code = code,
                    name = "$rawName(${info.take(10)})",
                    maxUses = (item["maxUses"] as Number).toInt(),
                    effect = item["effect"] as String,
                    value = (item["value"] as Number).toInt(),
                )
            } else {
                @Suppress("UNCHECKED_CAST")
                val statMap = item["stat"] as? Map<String, Number> ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val opposeStatMap = item["opposeStat"] as? Map<String, Number> ?: emptyMap()

                resultItems[code] = MiscItem(
                    code = code,
                    name = rawName,
                    statMods = statMap.mapValues { it.value.toDouble() },
                    opposeStatMods = opposeStatMap.mapValues { it.value.toDouble() },
                    triggerType = triggerType,
                )
            }
            resultMeta[code] = ItemMeta(code, rawName, "misc", 0, cost, buyable, rarity,
                consumable, info)
        }

        items = resultItems
        itemMeta = resultMeta
        itemTriggerTypes = resultTriggerTypes
    }

    fun get(code: String): ActionModifier? = items[code]
    fun getAll(): Map<String, ActionModifier> = items
    fun getMeta(code: String): ItemMeta? = itemMeta[code]
    fun getAllMeta(): Map<String, ItemMeta> = itemMeta
    fun getByCategory(category: String): List<ItemMeta> =
        itemMeta.values.filter { it.category == category }

    fun getBattleTriggers(code: String): List<BattleTrigger> {
        val meta = itemMeta[code] ?: return emptyList()
        val triggerType = itemTriggerTypes[code] ?: return emptyList()
        if (meta.consumable) {
            val consumable = items[code] as? ConsumableItem
            if (consumable?.effect == "battleTrain") {
                return listOf(BattleTriggerRegistry.get("che_훈련Init")!!)
            }
            return emptyList()
        }
        return when (triggerType) {
            "snipe" -> listOf(BattleTriggerRegistry.get("che_저격")!!)
            "rage" -> listOf(BattleTriggerRegistry.get("che_격노")!!)
            "siege" -> listOf(BattleTriggerRegistry.get("che_공성")!!)
            "intimidate" -> listOf(BattleTriggerRegistry.get("che_위압")!!)
            "block" -> listOf(BattleTriggerRegistry.get("che_저지")!!)
            "suppress" -> listOf(BattleTriggerRegistry.get("che_진압")!!)
            "antiSnipe" -> listOf(BattleTriggerRegistry.get("che_부적")!!)
            "plunder" -> listOf(BattleTriggerRegistry.get("che_약탈_try")!!, BattleTriggerRegistry.get("che_약탈_fire")!!)
            else -> emptyList()
        }
    }
}

data class ItemMeta(
    val code: String,
    val rawName: String,
    val category: String,
    val grade: Int,
    val cost: Int,
    val buyable: Boolean,
    val rarity: Int,
    val consumable: Boolean = false,
    val info: String = "",
)

class StatItem(
    override val code: String,
    override val name: String,
    private val leadership: Double = 0.0,
    private val strength: Double = 0.0,
    private val intel: Double = 0.0,
    private val dodge: Double = 0.0,
    private val critical: Double = 0.0,
    private val magic: Double = 0.0,
    private val warPower: Double = 1.0,
) : ActionModifier {
    override fun onCalcStat(stat: StatContext) = stat.copy(
        leadership = stat.leadership + leadership,
        strength = stat.strength + strength,
        intel = stat.intel + intel,
        dodgeChance = stat.dodgeChance + dodge,
        criticalChance = stat.criticalChance + critical,
        magicChance = stat.magicChance + magic,
        warPower = stat.warPower * warPower,
    )
}

data class ConsumableItem(
    override val code: String,
    override val name: String,
    val maxUses: Int,
    val effect: String,
    val value: Int,
) : ActionModifier

class MiscItem(
    override val code: String,
    override val name: String,
    private val statMods: Map<String, Double> = emptyMap(),
    private val opposeStatMods: Map<String, Double> = emptyMap(),
    val triggerType: String? = null,
) : ActionModifier {
    private fun parseCrewType(raw: String): CrewType? {
        val code = raw.toIntOrNull() ?: return null
        return CrewType.fromCode(code)
    }

    private fun isRegionalOrCityCrewType(raw: String): Boolean {
        val code = raw.toIntOrNull() ?: return false
        if (code <= 0) return true
        val crewType = CrewType.fromCode(code) ?: return false
        if (crewType.armType == ArmType.CASTLE) return true
        return crewType.code % 100 != 0
    }

    override fun onCalcStat(stat: StatContext): StatContext {
        var s = stat
        statMods["leadership"]?.let { s = s.copy(leadership = s.leadership + it) }
        statMods["strength"]?.let { s = s.copy(strength = s.strength + it) }
        statMods["intel"]?.let { s = s.copy(intel = s.intel + it) }
        statMods["leadershipPercent"]?.let { s = s.copy(leadership = s.leadership * (1.0 + it)) }
        statMods["dodgeChance"]?.let { s = s.copy(dodgeChance = s.dodgeChance + it) }
        statMods["criticalChance"]?.let { s = s.copy(criticalChance = s.criticalChance + it) }
        statMods["bonusTrain"]?.let { s = s.copy(bonusTrain = s.bonusTrain + it) }
        statMods["bonusAtmos"]?.let { s = s.copy(bonusAtmos = s.bonusAtmos + it) }
        statMods["magicTrialProb"]?.let { s = s.copy(magicTrialProb = s.magicTrialProb + it) }
        statMods["magicSuccessProb"]?.let { s = s.copy(magicSuccessProb = s.magicSuccessProb + it) }
        statMods["magicSuccessDamage"]?.let { s = s.copy(magicSuccessDamage = s.magicSuccessDamage * it) }
        statMods["dexMultiplier"]?.let { s = s.copy(dexMultiplier = s.dexMultiplier * it) }
        statMods["expMultiplier"]?.let { s = s.copy(expMultiplier = s.expMultiplier * it) }
        statMods["injuryProb"]?.let { s = s.copy(injuryProb = s.injuryProb + it) }
        statMods["initWarPhase"]?.let { s = s.copy(initWarPhase = s.initWarPhase + it) }
        statMods["sabotageDefence"]?.let { s = s.copy(sabotageDefence = s.sabotageDefence + it) }

        if (triggerType == "progressiveStat") {
            val relYear = (s.year - s.startYear).coerceAtLeast(0)
            val progressiveBonus = floor(relYear / 4.0).coerceAtMost(12.0)
            if (progressiveBonus > 0) {
                if (statMods.containsKey("leadership")) {
                    s = s.copy(leadership = s.leadership + progressiveBonus)
                }
                if (statMods.containsKey("strength")) {
                    s = s.copy(strength = s.strength + progressiveBonus)
                }
                if (statMods.containsKey("intel")) {
                    s = s.copy(intel = s.intel + progressiveBonus)
                }
            }
        }

        if (triggerType == "typeAdvantage" || triggerType == "antiRegional") {
            val myCrewType = parseCrewType(s.crewType)
            val opponentCrewType = parseCrewType(s.opponentCrewType)
            if (triggerType == "typeAdvantage" && myCrewType != null && opponentCrewType != null) {
                if (myCrewType.getAttackCoef(opponentCrewType) >= 1.0) {
                    s = s.copy(warPower = s.warPower * 1.1)
                } else {
                    s = s.copy(warPower = s.warPower * 0.9)
                }
            }
            if (triggerType == "antiRegional") {
                if (isRegionalOrCityCrewType(s.opponentCrewType)) {
                    s = s.copy(warPower = s.warPower * 1.15)
                } else {
                    s = s.copy(warPower = s.warPower * 0.85)
                }
            }
        }

        if (triggerType == "perseverance") {
            val leadership = s.leadership.coerceAtLeast(1.0)
            val crew = (s.hpRatio.coerceIn(0.0, 1.0) * leadership * 100.0).coerceAtLeast(0.0)
            val crewRatio = (crew / (leadership * 100.0)).coerceIn(0.0, 1.0)
            s = s.copy(warPower = s.warPower * (1.0 + 0.6 * (1.0 - crewRatio)))
        }

        return s
    }

    override fun onCalcDomestic(ctx: DomesticContext): DomesticContext {
        var c = ctx
        statMods["domesticSuccess"]?.let { c = c.copy(successMultiplier = c.successMultiplier + it) }
        statMods["domesticSabotageSuccess"]?.let {
            if (c.actionCode == "계략") c = c.copy(successMultiplier = c.successMultiplier + it)
        }
        statMods["domesticSupplySuccess"]?.let {
            if (c.actionCode == "조달") c = c.copy(successMultiplier = c.successMultiplier + it)
        }
        statMods["domesticSupplyScore"]?.let {
            if (c.actionCode == "조달") c = c.copy(scoreMultiplier = c.scoreMultiplier * it)
        }
        return c
    }

    override fun onCalcStrategic(ctx: StrategicContext): StrategicContext {
        var c = ctx
        statMods["strategicDelay"]?.let { c = c.copy(delayMultiplier = c.delayMultiplier * it) }
        return c
    }

}
