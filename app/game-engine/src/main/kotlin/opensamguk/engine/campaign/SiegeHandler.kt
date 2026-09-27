package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.world.ProvinceCellIndex
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/** Direct actions 강공(`action.assault`)·항복 권고(`action.demandSurrender`). Both take no arguments. */
class SiegeHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?,
    private val metrics: LandMarchMetricSnapshot?,
    private val cells: ProvinceCellIndex?,
    private val outcomes: WarOutcomeListener = WarOutcomeListener.NONE,
) {
    fun handle(inputId: String, actorId: Int, argJson: String?): TurnOutcome {
        if (world.ruleProfile != RuleProfile.HWIHA)
            return TurnOutcome.Rejected(inputId, "WRONG_RULE_PROFILE", SiegeService.Failure.WRONG_RULE_PROFILE.message)
        if (argJson != null && argJson.trim() !in setOf("", "{}"))
            return TurnOutcome.Rejected(inputId, "INVALID_INPUT", "이 입력은 인자를 받지 않습니다.")
        if (topology == null || metrics == null || cells == null)
            return TurnOutcome.Rejected(inputId, "STATE_UNAVAILABLE", SiegeService.Failure.STATE_UNAVAILABLE.message)
        val service = SiegeService(world, recorder, topology, metrics, cells, outcomes)
        val failure = when (inputId) {
            ASSAULT -> service.assault(actorId)
            DEMAND_SURRENDER -> service.demandSurrender(actorId)
            else -> return TurnOutcome.Rejected(inputId, "UNKNOWN_INPUT", "등록되지 않은 입력입니다.")
        }
        return if (failure == null) TurnOutcome.Applied(inputId)
            else TurnOutcome.Rejected(inputId, failure.name, failure.message)
    }

    companion object {
        const val ASSAULT = "action.assault"
        const val DEMAND_SURRENDER = "action.demandSurrender"
    }
}
