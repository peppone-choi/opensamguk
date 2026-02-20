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

    override fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Int> {
        val dc = destCity!!
        val secuAmount = min(rng.nextInt(200, 401), dc.secu)
        val trustAmount = min(rng.nextInt(200, 401) / 50, dc.trust.toInt())

        pushLog("${dc.name}의 백성들이 동요하고 있습니다.")
        pushLog("${dc.name}에 ${actionName}이 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 치안이 ${secuAmount}, 민심이 ${trustAmount}만큼 감소하고, 장수 ${injuryCount}명이 부상 당했습니다.")

        return mapOf("secu" to -secuAmount, "trust" to -trustAmount)
    }
}
