package opensamguk.engine.court.office

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.Phase
import opensamguk.logic.input.InputHandler
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.office.OfficeAppointmentFlow
import opensamguk.logic.office.OfficeAppointmentOffer
import opensamguk.logic.office.OfficeOfferResponseCommand

/** Validates the prepared OFFICE route; cost approval is absent, so every valid reply stays denied. */
class OfficeOfferResponseHandler(private val world: InMemoryTurnWorld) {
    fun inputHandler(command: ImmediateInput, onResult: (CommandLifecycleResult) -> Unit): InputHandler =
        InputHandler { onResult(handle(command)) }

    fun handle(command: ImmediateInput): CommandLifecycleResult {
        fun deny(code: String, reason: String) = CommandLifecycleResult(
            type = "executionRejected", ok = false, commandKind = "COURT_DECISION",
            actionCode = command.inputId, generalId = command.generalId, code = code, reason = reason,
        )
        if (command.inputId != OfficeOfferResponseCommand.INPUT_ID) return deny("UNKNOWN_INPUT", "알 수 없는 응답입니다.")
        if (command.generalId <= 0 || !command.requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
            return deny("INVALID_REQUEST", "입력 식별자가 올바르지 않습니다.")
        val profile = try { world.ruleProfile } catch (_: IllegalArgumentException) {
            return deny("STATE_UNAVAILABLE", "월드의 규칙을 확인할 수 없습니다.")
        }
        if (profile != RuleProfile.HWIHA) return deny("WRONG_RULE_PROFILE", "이 월드에서 사용할 수 없는 응답입니다.")
        val actor = world.getGeneralById(command.generalId) ?: return deny("ACTOR_NOT_FOUND", "장수를 찾을 수 없습니다.")
        if (command.ownerUserId <= 0 || actor.userId?.toLongOrNull() != command.ownerUserId.toLong())
            return deny("FORBIDDEN", "자신의 장수만 응답할 수 있습니다.")
        val request = OfficeOfferResponseCommand.parse(command.argJson)
            ?: return deny("INVALID_REQUEST", "임명 제안 응답 원본을 확인해 주세요.")
        if (request.expectedWorldId != world.worldId) return deny("WORLD_MISMATCH", "응답의 월드가 일치하지 않습니다.")
        if (request.expectedOffer.request.candidateId != actor.id)
            return deny("FORBIDDEN", "제안을 받은 장수만 응답할 수 있습니다.")
        val stored = try { OfficeAppointmentOffer.read(actor.meta) } catch (_: IllegalArgumentException) {
            return deny("STATE_UNAVAILABLE", "저장된 임명 제안을 확인할 수 없습니다.")
        } ?: return deny("STATE_UNAVAILABLE", "저장된 임명 제안을 확인할 수 없습니다.")
        if (stored != request.expectedOffer) return deny("SOURCE_CHANGED", "임명 제안 원본이 변경되었습니다.")
        try {
            val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
            OfficeAppointmentFlow.respond(stored, request.accepted, now)
        } catch (_: IllegalArgumentException) {
            return deny("STATE_UNAVAILABLE", "임명 제안의 시각을 확인할 수 없습니다.")
        }
        // No recorder/executor is attached until an authoritative cost contract is supplied.
        return deny("POLICY_UNAVAILABLE", "임명 응답의 비용 규칙이 아직 승인되지 않았습니다.")
    }
}
