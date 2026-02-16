package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General

class che_치안강화(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : DomesticCommand(general, env, arg) {

    override val actionName = "치안 강화"
    override val cityKey = "secu"
    override val statKey = "strength"
    override val debuffFront = 1.0
}
