package opensamguk.logic.office

import opensamguk.logic.input.DispatchPolicy
import opensamguk.logic.input.Phase

data class OfficeAppointmentRequest(
    val issuerId: Int,
    val candidateId: Int,
    val officeId: String,
    val jurisdictionId: String,
    val seatCountyId: Int,
) {
    init {
        require(issuerId > 0 && candidateId > 0)
        require(officeId.isNotBlank() && jurisdictionId.isNotBlank() && seatCountyId > 0)
    }
}

data class OfficeAppointmentContext(
    val issuerId: Int,
    val issuerNationId: Int,
    val issuerIsRuler: Boolean,
    val candidateId: Int,
    val candidateNationId: Int,
    val candidateIsHuman: Boolean,
    val jurisdiction: OfficeJurisdictionSnapshot,
    val activeTenures: List<OfficeTenure>,
    val centralOfficeIds: Set<String>,
) {
    init { require(issuerId > 0 && candidateId > 0 && issuerNationId > 0 && candidateNationId >= 0) }
}

enum class OfficeAppointmentFailure {
    ACTOR_MISMATCH,
    CANDIDATE_MISMATCH,
    NOT_RULER,
    CANDIDATE_OUTSIDE_NATION,
    CENTRAL_REQUIRES_EDICT,
    UNKNOWN_OFFICE,
    COUNTY_OFFICE_USES_PLACEMENT,
    WRONG_JURISDICTION,
    EXCLUDED_JURISDICTION,
    SEAT_NOT_IN_JURISDICTION,
    SEAT_NOT_OWNED,
    OFFICE_OCCUPIED,
    CONCURRENT_LIMIT,
    TENURE_NOT_FOUND,
    OFFER_NOT_ACCEPTED,
    DUPLICATE_TENURE_ID,
}

sealed interface OfficeAppointmentAssessment {
    data class Allowed(val needsCandidateResponse: Boolean) : OfficeAppointmentAssessment
    data class Denied(val reason: OfficeAppointmentFailure) : OfficeAppointmentAssessment
}

/** Precheck and execution call the same assessment with a fresh authoritative snapshot. */
object OfficeAppointmentRules {
    fun assess(
        request: OfficeAppointmentRequest,
        context: OfficeAppointmentContext,
        catalog: OfficeCatalog,
        rules: OfficeRules,
    ): OfficeAppointmentAssessment {
        if (context.issuerId != request.issuerId) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.ACTOR_MISMATCH)
        if (context.candidateId != request.candidateId) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.CANDIDATE_MISMATCH)
        if (!context.issuerIsRuler) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.NOT_RULER)
        if (context.candidateNationId != context.issuerNationId) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.CANDIDATE_OUTSIDE_NATION)
        if (request.officeId in context.centralOfficeIds) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.CENTRAL_REQUIRES_EDICT)
        val definition = catalog.definition(request.officeId)
            ?: return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.UNKNOWN_OFFICE)
        if (definition.countyPlacementOnly) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.COUNTY_OFFICE_USES_PLACEMENT)
        if (request.jurisdictionId in definition.excludedJurisdictions) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.EXCLUDED_JURISDICTION)
        val properScope = when (definition.jurisdiction) {
            OfficeJurisdiction.ZHOU -> request.jurisdictionId.startsWith("zhou:")
            OfficeJurisdiction.JUN -> request.jurisdictionId.startsWith("hhs-group:")
            OfficeJurisdiction.COUNTY -> false
        }
        if (!properScope || context.jurisdiction.jurisdictionId != request.jurisdictionId) {
            return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.WRONG_JURISDICTION)
        }
        if (request.seatCountyId != context.jurisdiction.seatCountyId || request.seatCountyId !in context.jurisdiction.countyIds) {
            return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.SEAT_NOT_IN_JURISDICTION)
        }
        if (request.seatCountyId !in context.jurisdiction.ownedCountyIds) {
            return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.SEAT_NOT_OWNED)
        }
        if (context.activeTenures.any { it.isActive && it.jurisdictionId == request.jurisdictionId &&
                catalog.definition(it.officeId)?.jurisdiction == definition.jurisdiction }) {
            return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.OFFICE_OCCUPIED)
        }
        if (context.activeTenures.count { it.isActive && it.holderId == request.candidateId } >= rules.maximumConcurrentLocalTenures) {
            return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.CONCURRENT_LIMIT)
        }
        return OfficeAppointmentAssessment.Allowed(context.candidateIsHuman && request.candidateId != request.issuerId)
    }

    fun assessDismissal(
        issuerNationId: Int,
        issuerIsRuler: Boolean,
        tenureId: String,
        tenures: Collection<OfficeTenure>,
    ): OfficeAppointmentAssessment {
        require(tenures.map { it.id }.distinct().size == tenures.size) { "duplicate office tenure" }
        if (!issuerIsRuler) return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.NOT_RULER)
        if (tenures.none { it.id == tenureId && it.isActive && it.nationId == issuerNationId }) {
            return OfficeAppointmentAssessment.Denied(OfficeAppointmentFailure.TENURE_NOT_FOUND)
        }
        return OfficeAppointmentAssessment.Allowed(false)
    }
}

enum class OfficeOfferStatus { PENDING, ACCEPTED, REFUSED }

data class OfficeAppointmentOffer(
    val id: String,
    val request: OfficeAppointmentRequest,
    val issuedAt: Phase,
    val dueAt: Phase,
    val status: OfficeOfferStatus,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(dueAt > issuedAt)
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "id" to id,
        "request" to linkedMapOf(
            "issuerId" to request.issuerId, "candidateId" to request.candidateId,
            "officeId" to request.officeId, "jurisdictionId" to request.jurisdictionId,
            "seatCountyId" to request.seatCountyId),
        "issuedAt" to issuedAt.toMetaValue(), "dueAt" to dueAt.toMetaValue(), "status" to status.name,
    )

    companion object {
        const val META_KEY = "officeAppointmentOffer"

        fun read(meta: Map<String, Any?>): OfficeAppointmentOffer? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == setOf("version", "id", "request", "issuedAt", "dueAt", "status"))
            require(row["version"] == 1)
            val request = row["request"] as? Map<*, *> ?: invalid()
            require(request.keys == setOf("issuerId", "candidateId", "officeId", "jurisdictionId", "seatCountyId"))
            return OfficeAppointmentOffer(
                id = row["id"] as? String ?: invalid(),
                request = OfficeAppointmentRequest(
                    request["issuerId"] as? Int ?: invalid(), request["candidateId"] as? Int ?: invalid(),
                    request["officeId"] as? String ?: invalid(), request["jurisdictionId"] as? String ?: invalid(),
                    request["seatCountyId"] as? Int ?: invalid()),
                issuedAt = Phase.read(row["issuedAt"]), dueAt = Phase.read(row["dueAt"]),
                status = OfficeOfferStatus.valueOf(row["status"] as? String ?: invalid()),
            )
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid office appointment offer")
    }
}

object OfficeAppointmentFlow {
    fun issue(id: String, request: OfficeAppointmentRequest, now: Phase, candidateIsHuman: Boolean,
              dispatchPolicy: DispatchPolicy): OfficeAppointmentOffer =
        OfficeAppointmentOffer(id, request, now, now.plus(dispatchPolicy.responsePhases),
            if (candidateIsHuman) OfficeOfferStatus.PENDING else OfficeOfferStatus.ACCEPTED)

    /** As with dispatch, automatic acceptance at the deadline takes precedence over a late refusal. */
    fun respond(offer: OfficeAppointmentOffer, accept: Boolean?, now: Phase): OfficeAppointmentOffer {
        if (offer.status != OfficeOfferStatus.PENDING) return offer
        require(now >= offer.issuedAt)
        return when {
            now >= offer.dueAt -> offer.copy(status = OfficeOfferStatus.ACCEPTED)
            accept == true -> offer.copy(status = OfficeOfferStatus.ACCEPTED)
            accept == false -> offer.copy(status = OfficeOfferStatus.REFUSED)
            else -> offer
        }
    }
}
