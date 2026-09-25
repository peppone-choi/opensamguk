package opensamguk.logic.imperial

/** Strict world_state.meta payload. Missing means no edict state was seeded. */
object ImperialEdictCodec {
    const val META_KEY = "imperialEdicts"

    fun read(meta: Map<String, Any?>): List<ImperialEdict>? {
        if (META_KEY !in meta) return null
        val root = meta[META_KEY].record(setOf("schemaVersion", "edicts"))
        require(root.int("schemaVersion") == 1)
        val edicts = root.list("edicts").map { it.readEdict() }
        require(edicts.map { it.proposal.id }.distinct().size == edicts.size)
        edicts.forEach(::validate)
        return edicts
    }

    fun write(edicts: Collection<ImperialEdict>): Map<String, Any> {
        require(edicts.map { it.proposal.id }.distinct().size == edicts.size)
        edicts.forEach(::validate)
        return linkedMapOf(
            "schemaVersion" to 1,
            "edicts" to edicts.sortedBy { it.proposal.id }.map { it.toRecord() },
        )
    }

    private fun validate(edict: ImperialEdict) {
        val review = edict.review
        val reviewed = edict.stage != EdictStage.PROPOSED
        require(reviewed == (review != null))
        if (review != null) {
            require(review.emperorActorId == edict.proposal.emperorId && review.reviewedText.isNotBlank())
            require(edict.secret == (review.emperorDecision == EmperorEdictDecision.SECRET_ORDER))
            require((edict.stage == EdictStage.REFUSED) == (review.emperorDecision == EmperorEdictDecision.REFUSE))
        } else require(!edict.secret)
        val registered = edict.stage in setOf(EdictStage.REGISTERED, EdictStage.SEALED, EdictStage.DISPATCHED, EdictStage.DELIVERED, EdictStage.RESPONDED)
        require(registered == (edict.registrarId != null))
        require(registered == (edict.registerId != null))
        require(edict.registrarId == null || edict.registrarId > 0)
        require(edict.registerId == null || edict.registerId.isNotBlank())
        val sealed = edict.stage in setOf(EdictStage.SEALED, EdictStage.DISPATCHED, EdictStage.DELIVERED, EdictStage.RESPONDED)
        require(sealed == (edict.sealArtifactId != null))
        require(sealed == edict.sealAuthorityAccepted)
        require(edict.sealArtifactId == null || edict.sealArtifactId.isNotBlank())
        val dispatched = edict.stage in setOf(EdictStage.DISPATCHED, EdictStage.DELIVERED, EdictStage.RESPONDED)
        require(dispatched == (edict.courierId != null))
        require(edict.courierId == null || edict.courierId > 0)
        val delivered = edict.stage in setOf(EdictStage.DELIVERED, EdictStage.RESPONDED)
        require(delivered == (edict.deliveredToFactionId != null))
        if (delivered) require(edict.deliveredToFactionId == edict.proposal.recipientFactionId)
        require(edict.deliveredToFactionId == null || edict.deliveredToFactionId > 0)
        require((edict.stage == EdictStage.RESPONDED) == (edict.receipt != null))
        edict.receipt?.let { receipt ->
            require(receipt.recipientFactionId == edict.proposal.recipientFactionId)
            val officeId = edict.proposal.requestedOffice?.officeId
            require(receipt.acceptedOfficeIds.all { it == officeId })
            require(receipt.decision in setOf(EdictRecipientDecision.ACCEPT, EdictRecipientDecision.PARTIAL_ACCEPT) || receipt.acceptedOfficeIds.isEmpty())
            if (receipt.decision == EdictRecipientDecision.ACCEPT && officeId != null) {
                require(officeId in receipt.acceptedOfficeIds)
            }
        }
    }

    private fun ImperialEdict.toRecord(): Map<String, Any?> = linkedMapOf(
        "proposal" to proposal.toRecord(), "stage" to stage.name,
        "review" to review?.toRecord(), "secret" to secret,
        "registrarId" to registrarId, "registerId" to registerId,
        "sealArtifactId" to sealArtifactId, "sealAuthorityAccepted" to sealAuthorityAccepted,
        "courierId" to courierId, "deliveredToFactionId" to deliveredToFactionId,
        "receipt" to receipt?.toRecord(),
    )

    private fun ImperialEdictProposal.toRecord(): Map<String, Any?> = linkedMapOf(
        "id" to id, "imperialLineCode" to imperialLineCode, "proposerId" to proposerId,
        "emperorId" to emperorId, "recipientFactionId" to recipientFactionId,
        "proposedText" to proposedText, "requestedOffice" to requestedOffice?.toRecord(),
    )

    private fun CentralOfficeGrant.toRecord(): Map<String, Any> = linkedMapOf("officeId" to officeId, "holderId" to holderId)
    private fun EdictReview.toRecord(): Map<String, Any> = linkedMapOf(
        "emperorDecision" to emperorDecision.name, "courtFactionDecision" to courtFactionDecision.name,
        "emperorActorId" to emperorActorId, "reviewedText" to reviewedText,
    )
    private fun EdictReceipt.toRecord(): Map<String, Any> = linkedMapOf(
        "recipientFactionId" to recipientFactionId, "decision" to decision.name,
        "acceptedOfficeIds" to acceptedOfficeIds.sorted(),
    )

    private fun Any?.readEdict(): ImperialEdict {
        val r = record(setOf("proposal", "stage", "review", "secret", "registrarId", "registerId",
            "sealArtifactId", "sealAuthorityAccepted", "courierId", "deliveredToFactionId", "receipt"))
        return ImperialEdict(
            proposal = r["proposal"].readProposal(),
            stage = enumValueOf(r.string("stage")),
            review = r["review"]?.readReview(),
            secret = r.boolean("secret"),
            registrarId = r.optionalInt("registrarId"),
            registerId = r.optionalString("registerId"),
            sealArtifactId = r.optionalString("sealArtifactId"),
            sealAuthorityAccepted = r.boolean("sealAuthorityAccepted"),
            courierId = r.optionalInt("courierId"),
            deliveredToFactionId = r.optionalInt("deliveredToFactionId"),
            receipt = r["receipt"]?.readReceipt(),
        )
    }

    private fun Any?.readProposal(): ImperialEdictProposal {
        val r = record(setOf("id", "imperialLineCode", "proposerId", "emperorId", "recipientFactionId", "proposedText", "requestedOffice"))
        return ImperialEdictProposal(r.string("id"), r.string("imperialLineCode"), r.int("proposerId"),
            r.int("emperorId"), r.int("recipientFactionId"), r.string("proposedText"), r["requestedOffice"]?.readOffice())
    }

    private fun Any?.readOffice(): CentralOfficeGrant {
        val r = record(setOf("officeId", "holderId"))
        return CentralOfficeGrant(r.string("officeId"), r.int("holderId"))
    }

    private fun Any?.readReview(): EdictReview {
        val r = record(setOf("emperorDecision", "courtFactionDecision", "emperorActorId", "reviewedText"))
        return EdictReview(enumValueOf(r.string("emperorDecision")), enumValueOf(r.string("courtFactionDecision")),
            r.int("emperorActorId"), r.string("reviewedText"))
    }

    private fun Any?.readReceipt(): EdictReceipt {
        val r = record(setOf("recipientFactionId", "decision", "acceptedOfficeIds"))
        val ids = r.list("acceptedOfficeIds").map { it as? String ?: invalid() }
        require(ids.distinct().size == ids.size)
        return EdictReceipt(r.int("recipientFactionId"), enumValueOf(r.string("decision")), ids.toSet())
    }

    private fun Any?.record(fields: Set<String>): Map<*, *> {
        val record = this as? Map<*, *> ?: invalid()
        require(record.keys == fields)
        return record
    }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String)?.takeIf { it.isNotBlank() } ?: invalid()
    private fun Map<*, *>.optionalString(key: String): String? = this[key]?.let { (it as? String)?.takeIf(String::isNotBlank) ?: invalid() }
    private fun Map<*, *>.int(key: String): Int = this[key] as? Int ?: invalid()
    private fun Map<*, *>.optionalInt(key: String): Int? = this[key]?.let { it as? Int ?: invalid() }
    private fun Map<*, *>.boolean(key: String): Boolean = this[key] as? Boolean ?: invalid()
    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: invalid()
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid imperial edict meta")
}
