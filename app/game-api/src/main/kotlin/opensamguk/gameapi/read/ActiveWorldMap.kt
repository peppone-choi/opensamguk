package opensamguk.gameapi.read

import opensamguk.logic.world.ActiveWorldMap as LogicActiveWorldMap
import org.slf4j.LoggerFactory

object ActiveWorldMap {
    private val log = LoggerFactory.getLogger(ActiveWorldMap::class.java)

    fun requireName(world: WorldStateReadEntity): String {
        try {
            return LogicActiveWorldMap.requireName(world.config, world.meta)
        } catch (cause: IllegalStateException) {
            failWithIdentity(world, "has no active mapName", cause)
        } catch (cause: IllegalArgumentException) {
            failWithIdentity(world, "has invalid active mapName", cause)
        }
    }

    private fun failWithIdentity(world: WorldStateReadEntity, reason: String, cause: RuntimeException): Nothing {
        val message = "world_state id=${world.id} scenario=${world.scenarioCode} $reason: ${cause.message}"
        log.error(message)
        throw IllegalStateException(message, cause)
    }
}
