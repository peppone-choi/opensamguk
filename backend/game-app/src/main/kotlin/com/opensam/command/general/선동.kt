package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General
import kotlin.math.min
import kotlin.random.Random

class 선동(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : 화계(general, env, arg) {

    override val actionName = "선동"
    override val statType = "leadership"
    override val injuryGeneral = true

    override fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Any> {
        val dc = destCity!!

        val secuAmount = min(rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1), dc.secu)

        // Trust reduction: damage / 50, capped at current trust.
        // Legacy: number_format($trustAmount, 1) — keep one decimal place.
        val trustRawDamage = rng.nextInt(env.sabotageDamageMin, env.sabotageDamageMax + 1).toDouble() / 50.0
        val trustAmount = minOf(trustRawDamage, dc.trust.toDouble())
        // Round to 1 decimal place for display and storage (legacy parity)
        val trustAmountRounded = Math.round(trustAmount * 10.0) / 10.0

        val secuAmountText = "%,d".format(secuAmount)
        val trustAmountText = "%.1f".format(trustAmountRounded)

        pushGlobalActionLog("<G><b>${dc.name}</b></>의 백성들이 동요하고 있습니다.")
        pushLog("<G><b>${dc.name}</b></>에 ${actionName}${josa(actionName, "이")} 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 치안이 <C>${secuAmountText}</>, 민심이 <C>${trustAmountText}</>만큼 감소하고, 장수 <C>${injuryCount}</>명이 부상 당했습니다.")

        // Return trust as Double (not Int) to preserve decimal precision.
        // The CommandResultApplicator handles trust as float.
        return mapOf(
            "secu" to -secuAmount,
            "trust" to -trustAmountRounded,
            "state" to 32
        )
    }
}
