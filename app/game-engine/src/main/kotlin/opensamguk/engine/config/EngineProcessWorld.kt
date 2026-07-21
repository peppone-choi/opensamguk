package opensamguk.engine.config

import opensamguk.common.world.WorldId

class EngineProcessWorld(configuredWorldId: Int) {
    val worldId: WorldId = WorldId(configuredWorldId)
}
