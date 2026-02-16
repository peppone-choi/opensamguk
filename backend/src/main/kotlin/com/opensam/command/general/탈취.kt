package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

class 탈취(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : 화계(general, env, arg) {

    override val actionName = "탈취"
    override val statType = "strength"
    override val injuryGeneral = false

    override fun affectDestCity(rng: Random, injuryCount: Int): Map<String, Int> {
        val dc = destCity!!
        val yearCoef = sqrt(1.0 + (env.year - env.startYear) / 4.0) / 2.0
        val commRatio = if (dc.commMax > 0) dc.comm.toDouble() / dc.commMax else 0.0
        val agriRatio = if (dc.agriMax > 0) dc.agri.toDouble() / dc.agriMax else 0.0

        val gold = (rng.nextInt(200, 401) * dc.level * yearCoef * (0.25 + commRatio / 4.0)).roundToInt()
        val rice = (rng.nextInt(200, 401) * dc.level * yearCoef * (0.25 + agriRatio / 4.0)).roundToInt()

        pushLog("${dc.name}에서 금과 쌀을 도둑맞았습니다.")
        pushLog("${dc.name}에 ${actionName}이 성공했습니다. <1>${formatDate()}</>")
        pushLog("금${gold} 쌀${rice}을 획득했습니다.")

        return mapOf("comm" to -(gold / 12), "agri" to -(rice / 12))
    }
}
