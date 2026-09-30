package opensamguk.engine.turn

import opensamguk.logic.imperial.ImperialCandidate
import opensamguk.logic.imperial.ImperialDeathTransition
import opensamguk.logic.imperial.ImperialWorldCodec

/** Complete the imperial transition before national ruler succession or the general tombstone. */
object ImperialDeathHook {
    fun apply(world: InMemoryTurnWorld, deceasedGeneralId: Int, env: LifecycleEnv) {
        val meta = world.getState().meta
        if (ImperialWorldCodec.META_KEY !in meta) return
        val candidates = world.listGenerals().map { general ->
            ImperialCandidate(general.id, living = general.id != deceasedGeneralId)
        }
        val next = ImperialDeathTransition.apply(
            meta, deceasedGeneralId,
            requestId = "imperial.death:${env.year}:${env.month}:$deceasedGeneralId",
            scriptedSuccessorGeneralId = null,
            candidates = candidates,
            year = env.year, month = env.month, reasonCode = "GENERAL_DEATH",
        )
        if (next !== meta) {
            world.setGameEnvValue(ImperialWorldCodec.META_KEY, next.getValue(ImperialWorldCodec.META_KEY))
        }
    }
}
