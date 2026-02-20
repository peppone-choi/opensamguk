package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General

class che_상업투자(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : DomesticCommand(general, env, arg) {

    override val actionName = "상업 투자"
    override val cityKey = "comm"
    override val statKey = "intel"
    override val debuffFront = 0.5
}
