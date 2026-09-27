package opensamguk.logic.imperial

import kotlin.math.abs

enum class CourtSettlementStance { COERCIVE_CONTROL, COREGENT_PROTECTORATE, HONOR_AND_RESTORE }

enum class CourtProtectorBenefit {
    PETITION_PRIORITY, LOYALIST_LEGITIMACY, FORMAL_CAMPAIGN_COMMISSION,
    NOMINATION_ACCESS, JOINT_DRAFT, CAPITAL_DEFENSE,
    SECRETARIAT_STAFF_CONTROL, GUARD_CONTROL, COURIER_SELECTION,
}

enum class CourtProtectorBurden {
    SUPPLY_COURT, PAY_COURT, HONOR_EDICTS, RESTORE_INSTITUTIONS,
    HONOR_CHARTER, CONVENE_REVIEW, LEGITIMACY_EXPOSURE,
}

data class CourtSettlementProfile(
    val stance: CourtSettlementStance,
    val benefits: Set<CourtProtectorBenefit>,
    val burdens: Set<CourtProtectorBurden>,
)

object CourtSettlementProfiles {
    fun forStance(stance: CourtSettlementStance): CourtSettlementProfile = when (stance) {
        CourtSettlementStance.HONOR_AND_RESTORE -> CourtSettlementProfile(stance,
            setOf(CourtProtectorBenefit.PETITION_PRIORITY, CourtProtectorBenefit.LOYALIST_LEGITIMACY,
                CourtProtectorBenefit.FORMAL_CAMPAIGN_COMMISSION),
            setOf(CourtProtectorBurden.SUPPLY_COURT, CourtProtectorBurden.PAY_COURT,
                CourtProtectorBurden.HONOR_EDICTS, CourtProtectorBurden.RESTORE_INSTITUTIONS))
        CourtSettlementStance.COREGENT_PROTECTORATE -> CourtSettlementProfile(stance,
            setOf(CourtProtectorBenefit.NOMINATION_ACCESS, CourtProtectorBenefit.JOINT_DRAFT,
                CourtProtectorBenefit.CAPITAL_DEFENSE),
            setOf(CourtProtectorBurden.SUPPLY_COURT, CourtProtectorBurden.PAY_COURT,
                CourtProtectorBurden.HONOR_CHARTER, CourtProtectorBurden.CONVENE_REVIEW))
        CourtSettlementStance.COERCIVE_CONTROL -> CourtSettlementProfile(stance,
            setOf(CourtProtectorBenefit.SECRETARIAT_STAFF_CONTROL, CourtProtectorBenefit.GUARD_CONTROL,
                CourtProtectorBenefit.COURIER_SELECTION),
            setOf(CourtProtectorBurden.SUPPLY_COURT, CourtProtectorBurden.PAY_COURT,
                CourtProtectorBurden.LEGITIMACY_EXPOSURE))
    }
}

data class CourtProtectionEvidence(
    val courtCityId: Int,
    val protectorNationId: Int,
    val protectorControlsCourtCity: Boolean,
    val emperorSafelyArrived: Boolean,
    val courtGuardSupplied: Boolean,
    val courtProvisioned: Boolean,
    val secretariatOperating: Boolean,
    val sealAdministrationOperating: Boolean,
) {
    init { require(courtCityId > 0 && protectorNationId > 0) }
}

data class CourtSettlementEvent(
    val requestId: String,
    val turn: Long,
    val from: CourtSettlementStance?,
    val to: CourtSettlementStance,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")) && turn >= 0)
    }
}

data class CourtSettlementState(
    val lineCode: String,
    val protectorNationId: Int,
    val courtCityId: Int,
    val stance: CourtSettlementStance,
    val history: List<CourtSettlementEvent>,
) {
    init {
        require(lineCode.isNotBlank() && protectorNationId > 0 && courtCityId > 0)
        require(history.isNotEmpty() && history.first().from == null)
        require(history.last().to == stance)
        require(history.map { it.requestId }.distinct().size == history.size)
        require(history.zipWithNext().all { (before, after) ->
            before.turn <= after.turn && after.from == before.to && abs(after.to.ordinal - before.to.ordinal) == 1
        })
    }
}

/** Court protection starts only after administrative settlement, never on city capture alone. */
object CourtSettlement {
    fun adopt(
        world: ImperialWorldState,
        lineCode: String,
        evidence: CourtProtectionEvidence,
        stance: CourtSettlementStance,
        requestId: String,
        turn: Long,
    ): CourtSettlementState {
        validateCourt(world, lineCode, evidence)
        return CourtSettlementState(lineCode, evidence.protectorNationId, evidence.courtCityId, stance,
            listOf(CourtSettlementEvent(requestId, turn, null, stance)))
    }

    fun change(
        state: CourtSettlementState,
        world: ImperialWorldState,
        evidence: CourtProtectionEvidence,
        stance: CourtSettlementStance,
        requestId: String,
        turn: Long,
    ): CourtSettlementState {
        validateCourt(world, state.lineCode, evidence)
        require(evidence.protectorNationId == state.protectorNationId && evidence.courtCityId == state.courtCityId)
        require(state.history.none { it.requestId == requestId })
        require(turn >= state.history.last().turn)
        require(abs(stance.ordinal - state.stance.ordinal) == 1) { "court stance changes one step at a time" }
        return state.copy(stance = stance, history = state.history + CourtSettlementEvent(requestId, turn, state.stance, stance))
    }

    private fun validateCourt(world: ImperialWorldState, lineCode: String, evidence: CourtProtectionEvidence) {
        val house = world.houses.single { it.code == lineCode }
        require(house.status == ImperialLineStatus.ACTIVE && house.holderGeneralId != null)
        require(house.courtCityId == evidence.courtCityId)
        require(evidence.protectorControlsCourtCity && evidence.emperorSafelyArrived)
        require(evidence.courtGuardSupplied && evidence.courtProvisioned)
        require(evidence.secretariatOperating && evidence.sealAdministrationOperating)
    }
}
