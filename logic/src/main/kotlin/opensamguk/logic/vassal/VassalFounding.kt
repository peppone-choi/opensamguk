package opensamguk.logic.vassal

/** Facts supplied from the 1층 retinue and lord-status event ledgers. */
data class VassalFoundingCandidate(
    val generalId: Int,
    val nationId: Int,
    val retinueOwnerId: Int?,
    val isLord: Boolean,
    val isHuman: Boolean,
) {
    init { require(generalId > 0 && nationId >= 0 && (retinueOwnerId == null || retinueOwnerId > 0)) }
}

enum class VassalFoundingFailure {
    NOT_RULER,
    ISSUER_MISMATCH,
    CANDIDATE_MISMATCH,
    CANDIDATE_OUTSIDE_NATION,
    CANDIDATE_NOT_DIRECT_RETINUE,
    CANDIDATE_ALREADY_LORD,
    CANDIDATE_ALREADY_VASSAL,
    CONTRACT_ID_USED,
    WRONG_TURN,
    FIEF_NOT_OWNED,
    FIEF_ALREADY_GRANTED,
    TRIBUTE_OUT_OF_RANGE,
}

sealed interface VassalFoundingAssessment {
    data class Allowed(val needsCandidateConsent: Boolean) : VassalFoundingAssessment
    data class Denied(val reason: VassalFoundingFailure) : VassalFoundingAssessment
}

/** Apply the release, lord-status event, and contract write atomically in the turn handler. */
data class VassalFoundingPlan(
    val releaseFromLordId: Int,
    val promoteGeneralIdToLord: Int,
    val contract: VassalContract,
)

object VassalFounding {
    fun assess(
        proposed: VassalContract,
        issuer: VassalLord,
        candidate: VassalFoundingCandidate,
        countyNationById: Map<Int, Int>,
        contracts: Collection<VassalContract>,
        rules: VassalRules,
        atTurn: Long,
    ): VassalFoundingAssessment {
        require(atTurn >= 0 && contracts.map { it.id }.distinct().size == contracts.size)
        if (!issuer.isLord || !issuer.isRuler) return VassalFoundingAssessment.Denied(VassalFoundingFailure.NOT_RULER)
        if (proposed.sovereignLordId != issuer.id || proposed.nationId != issuer.nationId)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.ISSUER_MISMATCH)
        if (proposed.vassalLordId != candidate.generalId)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_MISMATCH)
        if (candidate.nationId != issuer.nationId)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_OUTSIDE_NATION)
        if (candidate.isLord)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_ALREADY_LORD)
        if (candidate.retinueOwnerId != issuer.id)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_NOT_DIRECT_RETINUE)
        if (contracts.any { it.id == proposed.id })
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.CONTRACT_ID_USED)
        if (proposed.signedTurn != atTurn)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.WRONG_TURN)
        val active = contracts.filter { it.activeAt(atTurn) }
        if (active.any { it.vassalLordId == candidate.generalId })
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.CANDIDATE_ALREADY_VASSAL)
        if (proposed.fiefCountyIds.any { countyNationById[it] != issuer.nationId })
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.FIEF_NOT_OWNED)
        if (active.any { it.fiefCountyIds.any(proposed.fiefCountyIds::contains) })
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.FIEF_ALREADY_GRANTED)
        if (proposed.tributePercent !in rules.minimumTributePercent..rules.maximumTributePercent)
            return VassalFoundingAssessment.Denied(VassalFoundingFailure.TRIBUTE_OUT_OF_RANGE)
        return VassalFoundingAssessment.Allowed(candidate.isHuman)
    }

    fun plan(
        proposed: VassalContract,
        issuer: VassalLord,
        candidate: VassalFoundingCandidate,
        countyNationById: Map<Int, Int>,
        contracts: Collection<VassalContract>,
        rules: VassalRules,
        atTurn: Long,
        candidateConsented: Boolean,
    ): VassalFoundingPlan {
        val assessment = assess(proposed, issuer, candidate, countyNationById, contracts, rules, atTurn)
        require(assessment is VassalFoundingAssessment.Allowed) { "vassal founding denied: $assessment" }
        require(!assessment.needsCandidateConsent || candidateConsented) { "human candidate consent missing" }
        return VassalFoundingPlan(issuer.id, candidate.generalId, proposed)
    }
}
