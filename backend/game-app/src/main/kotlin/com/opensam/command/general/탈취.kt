package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

private const val MIN_NATIONAL_GOLD = 1000
private const val MIN_NATIONAL_RICE = 1000

class 탈취(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : 화계(general, env, arg) {

    override val actionName = "탈취"
    override val statType = "strength"
    override val injuryGeneral = false

    /**
     * 탈취 affectDestCity:
     * - yearCoef = sqrt(1 + (year - startYear) / 4) / 2
     * - gold/rice based on city level, comm/agri ratio, yearCoef
     * - If city is supplied: steal from dest nation treasury (capped at minNationalGold/Rice)
     * - If city is NOT supplied: reduce city comm/agri by stolen/12
     * - 70% of stolen goes to own nation, 30% to general (if in nation)
     * - If neutral (nationId == 0), general keeps 100%
     */
    override fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Any> {
        val dc = destCity!!
        val yearCoef = sqrt(1.0 + (env.year - env.startYear) / 4.0) / 2.0
        val commRatio = if (dc.commMax > 0) dc.comm.toDouble() / dc.commMax else 0.0
        val agriRatio = if (dc.agriMax > 0) dc.agri.toDouble() / dc.agriMax else 0.0

        var gold = (rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1) * dc.level * yearCoef * (0.25 + commRatio / 4.0)).roundToInt()
        var rice = (rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1) * dc.level * yearCoef * (0.25 + agriRatio / 4.0)).roundToInt()

        val isSupplied = dc.supplyState > 0 && destNation != null

        // If supplied: cap by dest nation's gold/rice at minNationalGold/Rice
        // Legacy: if(destNationGold - gold < minNationalGold) { gold += destNationGold - minNationalGold; ... }
        if (isSupplied) {
            val dn = destNation
            if (dn != null) {
                val destNationGold = dn.gold
                val destNationRice = dn.rice
                if (destNationGold - gold < MIN_NATIONAL_GOLD) {
                    gold = (destNationGold - MIN_NATIONAL_GOLD).coerceAtLeast(0)
                }
                if (destNationRice - rice < MIN_NATIONAL_RICE) {
                    rice = (destNationRice - MIN_NATIONAL_RICE).coerceAtLeast(0)
                }
            }
        }

        pushGlobalActionLog("<G><b>${dc.name}</b></>에서 금과 쌀을 도둑맞았습니다.")
        pushLog("<G><b>${dc.name}</b></>에 ${actionName}${josa(actionName, "이")} 성공했습니다. <1>${formatDate()}</>")
        pushLog("금<C>${"%,d".format(gold)}</> 쌀<C>${"%,d".format(rice)}</>을 획득했습니다.")

        val nationId = general.nationId
        val nationShareGold: Int
        val nationShareRice: Int
        val generalShareGold: Int
        val generalShareRice: Int

        if (nationId != 0L) {
            nationShareGold = (gold * 0.7).roundToInt()
            nationShareRice = (rice * 0.7).roundToInt()
            generalShareGold = gold - nationShareGold
            generalShareRice = rice - nationShareRice
        } else {
            nationShareGold = 0
            nationShareRice = 0
            generalShareGold = gold
            generalShareRice = rice
        }

        return if (isSupplied) {
            // Supplied: steal from dest nation treasury, no city stat damage
            mapOf(
                "_supplied" to 1,
                "_stolenGold" to gold,
                "_stolenRice" to rice,
                "_nationShareGold" to nationShareGold,
                "_nationShareRice" to nationShareRice,
                "_generalShareGold" to generalShareGold,
                "_generalShareRice" to generalShareRice,
                "state" to 34
            )
        } else {
            // Not supplied: reduce city comm/agri
            mapOf(
                "comm" to -(gold / 12),
                "agri" to -(rice / 12),
                "_stolenGold" to gold,
                "_stolenRice" to rice,
                "_nationShareGold" to nationShareGold,
                "_nationShareRice" to nationShareRice,
                "_generalShareGold" to generalShareGold,
                "_generalShareRice" to generalShareRice,
                "state" to 34
            )
        }
    }
}
