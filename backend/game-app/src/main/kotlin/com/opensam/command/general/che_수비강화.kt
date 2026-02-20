package com.opensam.command.general

import com.opensam.command.CommandEnv
import com.opensam.entity.General

class che_수비강화(general: General, env: CommandEnv, arg: Map<String, Any>? = null)
    : DomesticCommand(general, env, arg) {

    override val actionName = "수비 강화"
    override val cityKey = "def"
    override val statKey = "strength"
    override val debuffFront = 0.5
}
