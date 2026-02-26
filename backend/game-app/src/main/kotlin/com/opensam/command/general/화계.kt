package com.opensam.command.general

import com.opensam.command.CommandCost
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.GeneralCommand
import com.opensam.command.constraint.*
import com.opensam.engine.modifier.ConsumableItem
import com.opensam.engine.modifier.DomesticContext
import com.opensam.engine.modifier.ItemModifiers
import com.opensam.engine.modifier.StatContext
import com.opensam.entity.General
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

private const val SABOTAGE_DEFAULT_PROB = 0.2
private const val MAX_SUCCESS_PROB = 0.5
private const val INJURY_MAX = 80
private const val INJURY_PROB_DEFAULT = 0.3

open class 화계(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : GeneralCommand(general, env, arg) {

    override val actionName = "화계"

    protected open val statType: String = "intel"
    protected open val injuryGeneral: Boolean = true

    override val fullConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                SuppliedCity(),
                NotOccupiedDestCity(),
                NotNeutralDestCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
                DisallowDiplomacyBetweenStatus(mapOf(7 to "불가침국입니다.")),
            )
        }

    override val minConditionConstraints: List<Constraint>
        get() {
            val cost = getCost()
            return listOf(
                NotBeNeutral(),
                OccupiedCity(),
                SuppliedCity(),
                ReqGeneralGold(cost.gold),
                ReqGeneralRice(cost.rice),
            )
        }

    override fun getCost(): CommandCost {
        val cost = (env.develCost * 0.25).toInt()
        return CommandCost(gold = cost, rice = cost)
    }

    override fun getPreReqTurn() = 0
    override fun getPostReqTurn() = 0

    protected fun getStatScore(): Int = when (statType) {
        "leadership" -> general.leadership.toInt()
        "strength" -> general.strength.toInt()
        else -> general.intel.toInt()
    }

    protected fun getStatExpKey(): String = when (statType) {
        "leadership" -> "leadershipExp"
        "strength" -> "strengthExp"
        else -> "intelExp"
    }

    /**
     * Calculate attack success probability based on attacker stat.
     * Legacy: genScore / sabotageProbCoefByStat, then onCalcDomestic('계략', 'success', prob)
     */
    protected fun calcAttackProb(): Double {
        var prob = getStatScore() / env.sabotageProbCoefByStat.toDouble()

        // Apply onCalcDomestic modifiers (legacy: $general->onCalcDomestic('계략', 'success', $prob))
        val baseCtx = DomesticContext(successMultiplier = prob, actionCode = "계략")
        val modified = modifiers.fold(baseCtx) { ctx, modifier -> modifier.onCalcDomestic(ctx) }
        prob = modified.successMultiplier

        return prob
    }

    /**
     * Calculate defence probability based on dest city generals.
     * Legacy factors:
     * - max stat of defending generals / sabotageProbCoefByStat
     * - onCalcStat(sabotageDefence) correction per defender
     * - log2(affectCount + 1) - 1.25 * sabotageDefenceCoefByGeneralCnt
     * - city secu / secu_max / 5 (up to 20%p)
     * - supplied city: +0.1
     */
    protected fun calcDefenceProb(): Double {
        val dc = destCity ?: return 0.0
        val destNationId = dc.nationId
        val defenders = destCityGenerals ?: emptyList()

        var maxStat = 0
        var probCorrection = 0.0
        var affectCount = 0

        for (defender in defenders) {
            if (defender.nationId != destNationId) continue
            affectCount++
            val defStat = when (statType) {
                "leadership" -> defender.leadership.toInt()
                "strength" -> defender.strength.toInt()
                else -> defender.intel.toInt()
            }
            maxStat = max(maxStat, defStat)

            // Legacy: $probCorrection = $destGeneral->onCalcStat($destGeneral, 'sabotageDefence', $probCorrection)
            // Apply per-defender sabotageDefence modifier
            val baseStat = StatContext(sabotageDefence = probCorrection)
            // NOTE: We don't have per-defender modifiers here; this would require loading each defender's modifiers.
            // For now, use the base probCorrection. Full per-defender modifier support requires ModifierService access.
            probCorrection = baseStat.sabotageDefence
        }

        var prob = maxStat / env.sabotageProbCoefByStat.toDouble()
        prob += probCorrection
        prob += (ln((affectCount + 1).toDouble()) / ln(2.0) - 1.25) * env.sabotageDefenceCoefByGeneralCnt
        prob += if (dc.secuMax > 0) dc.secu.toDouble() / dc.secuMax / 5.0 else 0.0
        prob += if (dc.supplyState > 0) 0.1 else 0.0

        return prob
    }

    /**
     * Affect the destination city on successful sabotage.
     * Returns a map of city field deltas (e.g., "agri" to -amount).
     * Keys starting with "_" are metadata, not city changes.
     */
    protected open fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Any> {
        val dc = destCity!!
        val agriAmount = min(
            max(rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1), 0),
            dc.agri
        )
        val commAmount = min(
            max(rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1), 0),
            dc.comm
        )

        pushGlobalActionLog("<G><b>${dc.name}</b></>${josa(dc.name, "이")} 불타고 있습니다.")
        pushLog("<G><b>${dc.name}</b></>에 ${actionName}${josa(actionName, "이")} 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 농업이 <C>${agriAmount}</>, 상업이 <C>${commAmount}</>만큼 감소하고, 장수 <C>${injuryCount}</>명이 부상 당했습니다.")

        return mapOf("agri" to -agriAmount, "comm" to -commAmount, "state" to 32)
    }

    /**
     * Calculate and apply injury to dest city generals (legacy: SabotageInjury).
     * Legacy: for each defender in same nation, roll injuryProb (default 0.3, modified by onCalcStat).
     * On hit: injury +1..16 (capped at 80), crew*0.98, atmos*0.98, train*0.98.
     *
     * Directly modifies the defender General entities (saved by CommandExecutor).
     * Returns the number of injured generals.
     */
    protected fun calculateAndApplyInjuries(rng: Random): Int {
        if (!injuryGeneral) return 0

        val dc = destCity ?: return 0
        val defenders = destCityGenerals ?: emptyList()
        var injuryCount = 0

        for (defender in defenders) {
            if (defender.nationId != dc.nationId) continue

            // Legacy: injuryProb = 0.3, then onCalcStat($general, 'injuryProb', $injuryProb)
            val injuryProb = INJURY_PROB_DEFAULT
            if (rng.nextDouble() >= injuryProb) continue

            val injuryAmount = rng.nextInt(1, 17) // 1-16
            defender.injury = min(defender.injury.toInt() + injuryAmount, INJURY_MAX).toShort()
            defender.crew = (defender.crew * 0.98).toInt()
            defender.atmos = (defender.atmos * 0.98).toInt().toShort()
            defender.train = (defender.train * 0.98).toInt().toShort()

            injuryCount++
        }

        return injuryCount
    }

    /**
     * Check if the general's consumable item triggers on sabotage success.
     * Legacy: $itemObj->tryConsumeNow($general, 'GeneralCommand', '계략')
     * Returns true if item should be consumed, plus the item's display name.
     */
    protected fun checkConsumableItem(): Pair<Boolean, String?> {
        if (general.itemCode == "None") return Pair(false, null)

        val itemCode = general.itemCode
        val itemModifier = modifiers.find { it.code == itemCode }

        if (itemModifier is ConsumableItem && itemModifier.effect == "sabotageSuccess") {
            val meta = ItemModifiers.getMeta(itemCode)
            return Pair(true, meta?.rawName ?: itemCode)
        }

        return Pair(false, null)
    }

    override suspend fun run(rng: Random): CommandResult {
        val date = formatDate()
        val dc = destCity!!
        val destCityName = dc.name

        // Distance factor (legacy: searchDistance, default 99 if not found)
        val dist = getDistanceTo(dc.id) ?: 1

        val attackProb = calcAttackProb()
        val defenceProb = calcDefenceProb()
        var prob = SABOTAGE_DEFAULT_PROB + attackProb - defenceProb
        prob /= dist
        prob = max(0.0, min(MAX_SUCCESS_PROB, prob))

        val cost = getCost()

        if (rng.nextDouble() >= prob) {
            pushLog("<G><b>${destCityName}</b></>에 ${actionName}${josa(actionName, "이")} 실패했습니다. <1>$date</>")

            val exp = rng.nextInt(1, 101)
            val ded = rng.nextInt(1, 71)

            return CommandResult(
                success = false,
                logs = logs,
                message = """{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"${getStatExpKey()}":1}}"""
            )
        }

        // Calculate and apply injuries to defending generals (modifies entities directly)
        val injuryCount = calculateAndApplyInjuries(rng)

        val cityChanges = affectDestCity(rng, injuryCount)

        val exp = rng.nextInt(201, 301)
        val ded = rng.nextInt(141, 211)

        // Check consumable item (legacy: tryConsumeNow + deleteItem)
        val (consumeItem, consumeItemName) = checkConsumableItem()
        if (consumeItem && consumeItemName != null) {
            pushLog("<C>${consumeItemName}</>${josa(consumeItemName, "을")} 사용!")
        }

        // Build city changes JSON (non-underscore keys for destCityChanges)
        val publicCityChanges = cityChanges.filterKeys { !it.startsWith("_") }

        // Extra metadata from affectDestCity (탈취 resource transfer etc.)
        val extraMap = cityChanges.filterKeys { it.startsWith("_") }

        return CommandResult(
            success = true,
            logs = logs,
            message = buildString {
                append("""{"statChanges":{"gold":${-cost.gold},"rice":${-cost.rice},"experience":$exp,"dedication":$ded,"${getStatExpKey()}":1}""")

                // destCityChanges
                append(""","destCityChanges":{"cityId":${dc.id}""")
                for ((key, value) in publicCityChanges) {
                    when (value) {
                        is Double -> append(",\"$key\":$value")
                        is Float -> append(",\"$key\":$value")
                        else -> append(",\"$key\":$value")
                    }
                }
                append("}")

                // Own nation resource changes (탈취)
                val ownNationGold = extraMap["_nationShareGold"]
                val ownNationRice = extraMap["_nationShareRice"]
                if (ownNationGold != null || ownNationRice != null) {
                    append(",\"ownNationChanges\":{")
                    val parts = mutableListOf<String>()
                    if (ownNationGold != null) parts.add("\"gold\":$ownNationGold")
                    if (ownNationRice != null) parts.add("\"rice\":$ownNationRice")
                    append(parts.joinToString(","))
                    append("}")
                }

                // Dest nation resource changes (탈취)
                val stolenGold = extraMap["_stolenGold"]
                val stolenRice = extraMap["_stolenRice"]
                val isSupplied = extraMap["_supplied"]
                if (isSupplied != null && stolenGold != null && stolenRice != null) {
                    append(",\"destNationChanges\":{\"gold\":${-(stolenGold as Number).toInt()},\"rice\":${-(stolenRice as Number).toInt()}}")
                }

                // General resource share (탈취: general gets 30%)
                val generalGold = extraMap["_generalShareGold"]
                val generalRice = extraMap["_generalShareRice"]
                if (generalGold != null || generalRice != null) {
                    // Add to statChanges is already emitted, so we use a secondary statChanges merge
                    // Actually, append general gold/rice as additional statChanges
                    // We can't re-emit statChanges, so embed in extra
                    append(",\"extraStatChanges\":{")
                    val parts = mutableListOf<String>()
                    if (generalGold != null) parts.add("\"gold\":$generalGold")
                    if (generalRice != null) parts.add("\"rice\":$generalRice")
                    append(parts.joinToString(","))
                    append("}")
                }

                // Consumable item
                if (consumeItem) {
                    append(",\"consumeItem\":true")
                }

                append("}")
            }
        )
    }
}
