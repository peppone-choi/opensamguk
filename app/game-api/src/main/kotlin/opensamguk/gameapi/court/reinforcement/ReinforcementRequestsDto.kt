package opensamguk.gameapi.court.reinforcement

import com.fasterxml.jackson.annotation.JsonInclude
import opensamguk.logic.input.Phase
import opensamguk.logic.vassal.ReinforcementOutcome
import opensamguk.logic.vassal.ReinforcementReply

enum class ReinforcementRequestsStatus { READY, NOT_SEEDED, UNAVAILABLE }
enum class ReinforcementTargetStatus { AVAILABLE, NOT_APPLICABLE, UNAVAILABLE }
enum class ReinforcementCalendarStatus { READY, UNAVAILABLE }

/** Requests actually received by the actor as the recorded vassal recipient; never a nation-wide or issuer list. */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReinforcementRequestsDto(
    val status: ReinforcementRequestsStatus,
    val reason: String?,
    val now: Phase?,
    val requests: List<ReinforcementRequestDto>?,
)

/**
 * C5 accepted row. Turns are JSON safe integers. `responseDeadlineTurn` is the reply deadline; `decisionDueTurn` is the
 * departure/decision due from `VassalReinforcement.assess` and is never shown as the reply deadline. A missing reply is
 * `replyKind = null`, kept apart from REFUSE with 0 troops and from a zero obligation.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReinforcementRequestDto(
    val reinforcementRequestId: String,
    val contractId: String,
    val issuerGeneralName: String?,
    val issuerNationName: String?,
    val requestedTroops: Int,
    val obligatedTroops: Int,
    val minimumReducedTroops: Int,
    val responseDeadlineTurn: Long,
    val answeredTurn: Long?,
    val calendarStatus: ReinforcementCalendarStatus,
    val responseDeadlineAt: Phase?,
    val answeredAt: Phase?,
    val replyKind: ReinforcementReply?,
    val offeredTroops: Int?,
    val outcome: ReinforcementOutcome,
    val committedTroops: Int,
    val decisionDueTurn: Long,
    val target: ReinforcementTargetDto?,
    val targetStatus: ReinforcementTargetStatus,
    val execution: ReinforcementExecutionDto,
)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReinforcementTargetDto(val kind: String, val cityId: Int, val name: String?)

@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReinforcementExecutionDto(val status: String, val reason: String?)

data class ReinforcementRequestsErrorDto(val error: ReinforcementRequestsErrorDetailDto)
data class ReinforcementRequestsErrorDetailDto(val code: String, val message: String)
