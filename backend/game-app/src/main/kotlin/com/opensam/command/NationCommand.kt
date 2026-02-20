package com.opensam.command

import com.opensam.entity.General

abstract class NationCommand(
    general: General,
    env: CommandEnv,
    arg: Map<String, Any>? = null
) : BaseCommand(general, env, arg)
