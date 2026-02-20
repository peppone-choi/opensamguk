package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General

class che_성벽보수(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : DomesticCommand(general, env, arg) {

    override val actionName = "성벽 보수"
    override val cityKey = "wall"
    override val statKey = "strength"
    override val debuffFront = 0.25
}
