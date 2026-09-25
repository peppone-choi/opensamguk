package opensamguk.engine.hwiha

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.personalTurnSeed
import opensamguk.logic.input.EnlistmentFailure
import opensamguk.logic.input.EnlistmentInput
import opensamguk.logic.input.RuleProfile

/** Enlistment execution adapter; the personal-turn lifecycle owns slot/time/result persistence. */
class HwihaEnlistmentHandler(
    private val world: InMemoryTurnWorld,
    recorder: ChangeRecorder,
    private val hiddenSeed: String,
    private val rngFactory: (String) -> RandUtil = { RandUtil(LiteHashDrbg(it)) },
) {
    private val executor = HwihaEnlistmentExecutor(world, recorder)

    fun handle(actorId: Int, argJson: String?, year: Int, month: Int,
        inputId: String = INPUT_ID): HwihaTurnOutcome {
        fun reject(reason: EnlistmentFailure) = HwihaTurnOutcome.Rejected(inputId, reason.name, reason.message)
        if (world.ruleProfile != RuleProfile.HWIHA) return reject(EnlistmentFailure.WRONG_RULE_PROFILE)
        val request = EnlistmentInput.parse(actorId, inputId, argJson) ?: return reject(EnlistmentFailure.INVALID_REQUEST)
        // Construct and consume RNG only after fresh assessment yields multiple eligible choices.
        val rng by lazy { rngFactory(world.personalTurnSeed(hiddenSeed, "generalCommand", year, month, actorId, inputId)) }
        return when (val result = executor.execute(request) { count -> rng.nextInt(0, count) }) {
            is EnlistmentExecution.Applied -> HwihaTurnOutcome.Applied(inputId)
            is EnlistmentExecution.Rejected -> reject(result.reason)
        }
    }

    companion object { const val INPUT_ID = "action.enlist" }
}
