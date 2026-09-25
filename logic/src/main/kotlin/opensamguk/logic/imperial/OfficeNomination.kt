package opensamguk.logic.imperial

import opensamguk.logic.office.ActualJurisdiction
import opensamguk.logic.office.OfficeCapabilityResolver
import opensamguk.logic.office.OfficeClaimOrigin
import opensamguk.logic.office.OfficeJurisdictionSnapshot
import opensamguk.logic.office.OfficeRules
import opensamguk.logic.office.OfficeTenure

enum class NominationStatus {
    DRAFT, SUBMITTED, UNDER_REVIEW, OFFERED, ACCEPTED, DECLINED, DEFERRED, REJECTED, COMPETITOR_SELECTED,
}

enum class NominationReviewOutcome { ORIGINAL, LOWER_OFFICE, ACTING, DEFER, REJECT, COMPETITOR }

data class NominationReview(
    val outcome: NominationReviewOutcome,
    val reviewerId: Int,
    val offeredOfficeId: String? = null,
    val competitorId: Int? = null,
) {
    init {
        require(reviewerId > 0)
        require(offeredOfficeId == null || offeredOfficeId.isNotBlank())
        require(competitorId == null || competitorId > 0)
        require((outcome in setOf(NominationReviewOutcome.ORIGINAL, NominationReviewOutcome.LOWER_OFFICE, NominationReviewOutcome.ACTING)) == (offeredOfficeId != null))
        require((outcome == NominationReviewOutcome.COMPETITOR) == (competitorId != null))
    }
}

data class OfficeNomination(
    val id: String,
    val proposerId: Int,
    val candidateId: Int,
    val requestedOfficeId: String,
    val requestedJurisdictionId: String?,
    val grounds: String,
    val courtId: String,
    val status: NominationStatus = NominationStatus.DRAFT,
    val review: NominationReview? = null,
) {
    init {
        require(id.isNotBlank() && proposerId > 0 && candidateId > 0)
        require(requestedOfficeId.isNotBlank() && grounds.isNotBlank() && courtId.isNotBlank())
        require(requestedJurisdictionId == null || requestedJurisdictionId.isNotBlank())
    }
}

object OfficeNominationFlow {
    fun submit(nomination: OfficeNomination): OfficeNomination {
        require(nomination.status == NominationStatus.DRAFT)
        return nomination.copy(status = NominationStatus.SUBMITTED)
    }

    fun beginReview(nomination: OfficeNomination): OfficeNomination {
        require(nomination.status == NominationStatus.SUBMITTED)
        return nomination.copy(status = NominationStatus.UNDER_REVIEW)
    }

    fun decide(nomination: OfficeNomination, review: NominationReview): OfficeNomination {
        require(nomination.status == NominationStatus.UNDER_REVIEW)
        if (review.outcome == NominationReviewOutcome.ORIGINAL) require(review.offeredOfficeId == nomination.requestedOfficeId)
        if (review.outcome == NominationReviewOutcome.LOWER_OFFICE) require(review.offeredOfficeId != nomination.requestedOfficeId)
        if (review.outcome == NominationReviewOutcome.COMPETITOR) require(review.competitorId != nomination.candidateId)
        val status = when (review.outcome) {
            NominationReviewOutcome.ORIGINAL, NominationReviewOutcome.LOWER_OFFICE, NominationReviewOutcome.ACTING -> NominationStatus.OFFERED
            NominationReviewOutcome.DEFER -> NominationStatus.DEFERRED
            NominationReviewOutcome.REJECT -> NominationStatus.REJECTED
            NominationReviewOutcome.COMPETITOR -> NominationStatus.COMPETITOR_SELECTED
        }
        return nomination.copy(status = status, review = review)
    }

    fun respond(nomination: OfficeNomination, accept: Boolean, actorId: Int): OfficeNomination {
        require(nomination.status == NominationStatus.OFFERED)
        require(actorId == nomination.candidateId)
        return nomination.copy(status = if (accept) NominationStatus.ACCEPTED else NominationStatus.DECLINED)
    }

    fun resubmit(nomination: OfficeNomination): OfficeNomination {
        require(nomination.status == NominationStatus.DEFERRED)
        return nomination.copy(status = NominationStatus.SUBMITTED, review = null)
    }
}

enum class ClaimRecognition { RECOGNIZED, CONTESTED, REJECTED }

/** An append-only assertion, independent of OfficeTenure.origin. */
data class OfficeClaimRecord(
    val id: String,
    val officeId: String,
    val claimantId: Int,
    val origin: OfficeClaimOrigin,
    val issuerId: Int,
    val previousClaimId: String? = null,
    val nominationId: String? = null,
    val edictId: String? = null,
    val recognitionByPolity: Map<Int, ClaimRecognition> = emptyMap(),
) {
    init {
        require(id.isNotBlank() && officeId.isNotBlank() && claimantId > 0 && issuerId > 0)
        require(previousClaimId == null || previousClaimId.isNotBlank())
        require(nominationId == null || nominationId.isNotBlank())
        require(edictId == null || edictId.isNotBlank())
        require(recognitionByPolity.keys.all { it > 0 })
        if (origin == OfficeClaimOrigin.COURT_CONFIRMED) require(previousClaimId != null && edictId != null)
    }
}

data class CourtConfirmationProof(val edictId: String, val officeId: String, val claimantId: Int) {
    init { require(edictId.isNotBlank() && officeId.isNotBlank() && claimantId > 0) }
}

object OfficeClaims {
    fun selfStyle(id: String, officeId: String, claimantId: Int): OfficeClaimRecord =
        OfficeClaimRecord(id, officeId, claimantId, OfficeClaimOrigin.SELF_STYLED, claimantId)

    /** The caller must derive proof from an accepted, sealed imperial edict. */
    fun confirm(
        history: List<OfficeClaimRecord>,
        priorClaimId: String,
        newClaimId: String,
        courtIssuerId: Int,
        proof: CourtConfirmationProof,
    ): List<OfficeClaimRecord> {
        require(history.none { it.id == newClaimId })
        val prior = requireNotNull(history.singleOrNull { it.id == priorClaimId })
        require(prior.origin != OfficeClaimOrigin.COURT_CONFIRMED)
        require(prior.officeId == proof.officeId && prior.claimantId == proof.claimantId)
        require(history.none { it.origin == OfficeClaimOrigin.COURT_CONFIRMED && it.previousClaimId == prior.id })
        val confirmation = OfficeClaimRecord(
            id = newClaimId,
            officeId = prior.officeId,
            claimantId = prior.claimantId,
            origin = OfficeClaimOrigin.COURT_CONFIRMED,
            issuerId = courtIssuerId,
            previousClaimId = prior.id,
            nominationId = prior.nominationId,
            edictId = proof.edictId,
            recognitionByPolity = prior.recognitionByPolity,
        )
        return history.toList() + confirmation
    }

    fun recognizedEffectiveJurisdiction(
        claim: OfficeClaimRecord,
        tenure: OfficeTenure,
        polityId: Int,
        snapshot: OfficeJurisdictionSnapshot,
        rules: OfficeRules,
    ): ActualJurisdiction? {
        require(claim.officeId == tenure.officeId && claim.claimantId == tenure.holderId)
        if (claim.recognitionByPolity[polityId] != ClaimRecognition.RECOGNIZED) return null
        return OfficeCapabilityResolver.actualJurisdiction(tenure, snapshot, rules).takeIf { it.effective }
    }
}
