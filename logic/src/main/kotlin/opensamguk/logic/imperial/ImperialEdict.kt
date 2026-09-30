package opensamguk.logic.imperial

/** The proposal is a request. It does not itself confer an office or command a recipient. */
data class ImperialEdictProposal(
    val id: String,
    val imperialLineCode: String,
    val proposerId: Int,
    val emperorId: Int,
    val recipientFactionId: Int,
    val proposedText: String,
    val requestedOffice: CentralOfficeGrant? = null,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(imperialLineCode.matches(Regex("[a-z][a-z0-9_]{1,63}")) && proposedText.isNotBlank())
        require(proposerId > 0 && emperorId > 0 && recipientFactionId > 0)
    }
}

data class CentralOfficeGrant(val officeId: String, val holderId: Int) {
    init {
        require(officeId.isNotBlank() && holderId > 0)
    }
}

enum class EdictStage { PROPOSED, REVIEWED, REGISTERED, SEALED, DISPATCHED, DELIVERED, RESPONDED, REFUSED }
enum class EmperorEdictDecision { APPROVE, AMEND, REFUSE, SECRET_ORDER }
enum class CourtFactionDecision { SUPPORT, OPPOSE, COUNTERPROPOSAL }
enum class EdictRecipientDecision { ACCEPT, PARTIAL_ACCEPT, DELAY, REFUSE, DENOUNCE }

data class EdictReview(
    val emperorDecision: EmperorEdictDecision,
    val courtFactionDecision: CourtFactionDecision,
    val emperorActorId: Int,
    val reviewedText: String,
)

data class EdictReceipt(
    val recipientFactionId: Int,
    val decision: EdictRecipientDecision,
    val acceptedOfficeIds: Set<String> = emptySet(),
)

data class ImperialEdict(
    val proposal: ImperialEdictProposal,
    val stage: EdictStage = EdictStage.PROPOSED,
    val review: EdictReview? = null,
    val secret: Boolean = false,
    val registrarId: Int? = null,
    val registerId: String? = null,
    val sealArtifactId: String? = null,
    val sealAuthorityAccepted: Boolean = false,
    val courierId: Int? = null,
    val deliveredToFactionId: Int? = null,
    val receipt: EdictReceipt? = null,
)

/** Pure, ordered administrative pipeline. External adapters resolve actors, seal custody and route travel. */
object ImperialEdictPipeline {
    fun review(
        edict: ImperialEdict,
        emperorDecision: EmperorEdictDecision,
        courtFactionDecision: CourtFactionDecision,
        emperorActorId: Int,
        reviewedText: String,
    ): ImperialEdict {
        require(edict.stage == EdictStage.PROPOSED)
        require(emperorActorId == edict.proposal.emperorId)
        require(reviewedText.isNotBlank())
        val review = EdictReview(emperorDecision, courtFactionDecision, emperorActorId, reviewedText)
        return edict.copy(
            stage = if (emperorDecision == EmperorEdictDecision.REFUSE) EdictStage.REFUSED else EdictStage.REVIEWED,
            review = review,
            secret = emperorDecision == EmperorEdictDecision.SECRET_ORDER,
        )
    }

    fun register(edict: ImperialEdict, registrarId: Int, registerId: String): ImperialEdict {
        require(edict.stage == EdictStage.REVIEWED)
        require(registrarId > 0 && registerId.isNotBlank())
        return edict.copy(stage = EdictStage.REGISTERED, registrarId = registrarId, registerId = registerId)
    }

    fun seal(edict: ImperialEdict, artifactId: String, authorizedForEdicts: Boolean): ImperialEdict {
        require(edict.stage == EdictStage.REGISTERED)
        require(artifactId.isNotBlank() && authorizedForEdicts)
        return edict.copy(stage = EdictStage.SEALED, sealArtifactId = artifactId, sealAuthorityAccepted = true)
    }

    fun dispatch(edict: ImperialEdict, courierId: Int): ImperialEdict {
        require(edict.stage == EdictStage.SEALED && edict.sealAuthorityAccepted)
        require(courierId > 0)
        return edict.copy(stage = EdictStage.DISPATCHED, courierId = courierId)
    }

    fun deliver(edict: ImperialEdict, recipientFactionId: Int): ImperialEdict {
        require(edict.stage == EdictStage.DISPATCHED)
        require(recipientFactionId == edict.proposal.recipientFactionId)
        return edict.copy(stage = EdictStage.DELIVERED, deliveredToFactionId = recipientFactionId)
    }

    fun respond(edict: ImperialEdict, receipt: EdictReceipt): ImperialEdict {
        require(edict.stage == EdictStage.DELIVERED)
        require(receipt.recipientFactionId == edict.proposal.recipientFactionId)
        val office = edict.proposal.requestedOffice
        require(receipt.acceptedOfficeIds.all { office != null && it == office.officeId })
        require(
            when (receipt.decision) {
                EdictRecipientDecision.ACCEPT -> office == null || office.officeId in receipt.acceptedOfficeIds
                EdictRecipientDecision.PARTIAL_ACCEPT -> office != null
                EdictRecipientDecision.DELAY, EdictRecipientDecision.REFUSE, EdictRecipientDecision.DENOUNCE ->
                    receipt.acceptedOfficeIds.isEmpty()
            },
        )
        return edict.copy(stage = EdictStage.RESPONDED, receipt = receipt.copy(acceptedOfficeIds = receipt.acceptedOfficeIds.toSet()))
    }

    /** An adapter may create an OfficeTenure only from this proof and a catalogued central office. */
    fun acceptedCentralGrant(edict: ImperialEdict, catalog: CentralOfficeCatalog): CentralOfficeGrant? {
        if (edict.stage != EdictStage.RESPONDED || !edict.sealAuthorityAccepted) return null
        val review = edict.review ?: return null
        if (review.emperorActorId != edict.proposal.emperorId || review.emperorDecision == EmperorEdictDecision.REFUSE) return null
        if (edict.registrarId == null || edict.registerId == null || edict.sealArtifactId == null || edict.courierId == null) return null
        if (edict.deliveredToFactionId != edict.proposal.recipientFactionId) return null
        val grant = edict.proposal.requestedOffice ?: return null
        if (grant.officeId !in catalog.ids) return null
        val receipt = edict.receipt ?: return null
        if (receipt.decision !in setOf(EdictRecipientDecision.ACCEPT, EdictRecipientDecision.PARTIAL_ACCEPT)) return null
        return grant.takeIf { it.officeId in receipt.acceptedOfficeIds }
    }
}

/** Evidence is evaluated separately for every recipient. A seal alone is insufficient. */
enum class EdictCredibilityFactor {
    EMPEROR_ASSENT, SECRETARIAT_RECORD, AUTHENTIC_SEAL, COURT_WITNESS, RITUAL_CONTINUITY,
    TRUSTED_COURIER, PUBLIC_COERCION, FORGERY_OR_DUPLICATE,
    PROTECTOR_DEFIANCE, COURT_COLLAPSE,
}

data class EdictCredibilityRules(val weights: Map<EdictCredibilityFactor, Int>) {
    init {
        require(weights.keys == EdictCredibilityFactor.entries.toSet())
        require(weights.all { (factor, weight) ->
            if (factor in negativeFactors) weight < 0 else weight > 0
        })
    }

    companion object {
        private val negativeFactors = setOf(
            EdictCredibilityFactor.PUBLIC_COERCION,
            EdictCredibilityFactor.FORGERY_OR_DUPLICATE,
            EdictCredibilityFactor.PROTECTOR_DEFIANCE,
            EdictCredibilityFactor.COURT_COLLAPSE,
        )
    }
}

data class EdictRecipientAssessment(
    val factionId: Int,
    val evidence: Set<EdictCredibilityFactor>,
    val audienceModifier: Int,
    val politicalInterestModifier: Int,
) {
    init { require(factionId > 0) }
}

object EdictCredibility {
    fun score(rules: EdictCredibilityRules, assessment: EdictRecipientAssessment): Int =
        assessment.evidence.sumOf { rules.weights.getValue(it) } +
            assessment.audienceModifier + assessment.politicalInterestModifier
}
