package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General
import kotlin.math.min
import kotlin.random.Random

private const val SABOTAGE_DMG_MIN = 200
private const val SABOTAGE_DMG_MAX = 400

class 선동(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : 화계(general, env, arg) {

    override val actionName = "선동"
    override val statType = "leadership"
    override val injuryGeneral = true

    override fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Int> {
        val dc = destCity!!

        val secuAmount = min(rng.nextInt(SABOTAGE_DMG_MIN, SABOTAGE_DMG_MAX + 1), dc.secu)
        // Trust reduction: damage / 50, capped at current trust (legacy PHP parity)
        val trustRawDamage = rng.nextInt(SABOTAGE_DMG_MIN, SABOTAGE_DMG_MAX + 1).toDouble() / 50.0
        val trustAmount = minOf(trustRawDamage, dc.trust)

        val secuAmountText = "%,d".format(secuAmount)
        val trustAmountText = "%.1f".format(trustAmount)

        // Global action log (legacy: pushGlobalActionLog)
        pushLog("[GLOBAL]<G><b>${dc.name}</b></>의 백성들이 동요하고 있습니다.")
        pushLog("<G><b>${dc.name}</b></>에 ${actionName}이 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 치안이 <C>${secuAmountText}</>, 민심이 <C>${trustAmountText}</>만큼 감소하고, 장수 <C>${injuryCount}</>명이 부상 당했습니다.")

        return mapOf("secu" to -secuAmount, "trust" to -trustAmount.toInt(), "state" to 32)
    }
}
