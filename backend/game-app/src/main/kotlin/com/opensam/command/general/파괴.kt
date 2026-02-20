package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class 파괴(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : 화계(general, env, arg) {

    override val actionName = "파괴"
    override val statType = "strength"
    override val injuryGeneral = true

    override fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Int> {
        val dc = destCity!!
        val defAmount = min(max(rng.nextInt(200, 401), 0), dc.def)
        val wallAmount = min(max(rng.nextInt(200, 401), 0), dc.wall)

        pushLog("누군가가 ${dc.name}의 성벽을 허물었습니다.")
        pushLog("${dc.name}에 ${actionName}이 성공했습니다. <1>${formatDate()}</>")
        pushLog("도시의 수비가 ${defAmount}, 성벽이 ${wallAmount}만큼 감소하고, 장수 ${injuryCount}명이 부상 당했습니다.")

        return mapOf("def" to -defAmount, "wall" to -wallAmount)
    }
}
