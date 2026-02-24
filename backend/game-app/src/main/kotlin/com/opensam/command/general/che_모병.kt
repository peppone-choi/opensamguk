package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General

/**
 * 모병 command — recruit soldiers (higher cost, higher train/atmos).
 *
 * Legacy parity: che_모병.php extends che_징병.php
 * - costOffset = 2 (gold cost doubled, trust impact halved)
 * - defaultTrain/Atmos = 70/70 (vs 40/40 for 징병)
 */
class che_모병(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : che_징병(general, env, arg) {

    override val actionName = "모병"
    override val costOffset: Int = 2
    override val defaultTrain: Int = DEFAULT_TRAIN_HIGH
    override val defaultAtmos: Int = DEFAULT_ATMOS_HIGH

    companion object {
        const val DEFAULT_TRAIN_HIGH = 70
        const val DEFAULT_ATMOS_HIGH = 70
    }
}
