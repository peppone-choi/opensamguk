package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.world.HanProvinceCellIndex
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/** Direct actions 강공(`action.assault`)·항복 권고(`action.demandSurrender`). Both take no arguments. */
class HwihaSiegeHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?,
    private val metrics: LandMarchMetricSnapshot?,
    private val cells: HanProvinceCellIndex?,
) {
    fun handle(inputId: String, actorId: Int, argJson: String?): HwihaTurnOutcome {
        if (world.ruleProfile != RuleProfile.HWIHA)
            return HwihaTurnOutcome.Rejected(inputId, "WRONG_RULE_PROFILE", HwihaSiegeService.Failure.WRONG_RULE_PROFILE.message)
        if (argJson != null && argJson.trim() !in setOf("", "{}"))
            return HwihaTurnOutcome.Rejected(inputId, "INVALID_INPUT", "이 입력은 인자를 받지 않습니다.")
        if (topology == null || metrics == null || cells == null)
            return HwihaTurnOutcome.Rejected(inputId, "STATE_UNAVAILABLE", HwihaSiegeService.Failure.STATE_UNAVAILABLE.message)
        val service = HwihaSiegeService(world, recorder, topology, metrics, cells)
        val failure = when (inputId) {
            ASSAULT -> service.assault(actorId)
            DEMAND_SURRENDER -> service.demandSurrender(actorId)
            else -> return HwihaTurnOutcome.Rejected(inputId, "UNKNOWN_INPUT", "등록되지 않은 입력입니다.")
        }
        return if (failure == null) HwihaTurnOutcome.Applied(inputId)
            else HwihaTurnOutcome.Rejected(inputId, failure.name, failure.message)
    }

    companion object {
        const val ASSAULT = "action.assault"
        const val DEMAND_SURRENDER = "action.demandSurrender"
    }
}
