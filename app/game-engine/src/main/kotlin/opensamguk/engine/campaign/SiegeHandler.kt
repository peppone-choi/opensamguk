package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.input.SiegeAssaultInput
import opensamguk.logic.world.ProvinceCellIndex
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/** 강공 requires its selected county; 항복 권고 retains its no-argument contract. */
class SiegeHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val topology: StrategicTopologySnapshot?,
    private val metrics: LandMarchMetricSnapshot?,
    private val cells: ProvinceCellIndex?,
    private val outcomes: WarOutcomeListener = WarOutcomeListener.NONE,
) {
    fun handle(inputId: String, actorId: Int, argJson: String?, reservationOwnerUserId: Int? = null,
        npcSelected: Boolean = false): TurnOutcome {
        if (world.ruleProfile != RuleProfile.HWIHA)
            return TurnOutcome.Rejected(inputId, "WRONG_RULE_PROFILE", SiegeService.Failure.WRONG_RULE_PROFILE.message)
        val targetCountyId = if (inputId == ASSAULT) {
            val target = SiegeAssaultInput.parse(argJson)
                ?: return TurnOutcome.Rejected(inputId, "INVALID_INPUT", "강공할 縣을 골라 주세요.")
            if (!npcSelected && (reservationOwnerUserId == null || reservationOwnerUserId <= 0 ||
                    world.getGeneralById(actorId)?.userId != reservationOwnerUserId.toString()))
                return TurnOutcome.Rejected(inputId, "FORBIDDEN", "현재 이 장수로 강공할 권한이 없습니다.")
            target
        } else {
            if (argJson != null && argJson.trim() !in setOf("", "{}"))
                return TurnOutcome.Rejected(inputId, "INVALID_INPUT", "이 입력은 인자를 받지 않습니다.")
            null
        }
        if (topology == null || metrics == null || cells == null)
            return TurnOutcome.Rejected(inputId, "STATE_UNAVAILABLE", SiegeService.Failure.STATE_UNAVAILABLE.message)
        val service = SiegeService(world, recorder, topology, metrics, cells, outcomes)
        val failure = when (inputId) {
            ASSAULT -> service.assault(actorId, requireNotNull(targetCountyId))
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
