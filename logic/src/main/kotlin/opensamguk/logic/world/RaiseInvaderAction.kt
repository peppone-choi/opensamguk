package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

data class RaiseInvaderSpec(
    val npcEachCount: Double = -3.0,
    val specAvg: Double = -1.2,
    val tech: Double = -1.2,
    val dex: Double = -1.0,
)

interface RaiseInvaderContext : EventActionContext {
    fun raiseInvader(spec: RaiseInvaderSpec): Int
}

class RaiseInvaderAction(
    private val spec: RaiseInvaderSpec,
) : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx as? RaiseInvaderContext
            ?: error("RaiseInvader requires RaiseInvaderContext")
        world.raiseInvader(spec)
    }

    companion object {
        const val NAME = "RaiseInvader"

        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                RaiseInvaderAction(
                    RaiseInvaderSpec(
                        npcEachCount = args.numberAt(0, -3.0),
                        specAvg = args.numberAt(1, -1.2),
                        tech = args.numberAt(2, -1.2),
                        dex = args.numberAt(3, -1.0),
                    ),
                )
            }

        private fun List<JsonElement>.numberAt(index: Int, default: Double): Double {
            val value = getOrNull(index) ?: return default
            return (value as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.double
                ?: throw IllegalArgumentException("RaiseInvader argument $index must be numeric")
        }
    }
}
