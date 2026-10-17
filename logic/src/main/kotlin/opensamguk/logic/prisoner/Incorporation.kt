package opensamguk.logic.prisoner

/** Costs are the complete quote, not an invented per-soldier default. */
data class IncorporationTerms(val costs: Map<String, Long>? = null, val trainingTicks: Long? = null) {
    init {
        require(costs == null || costs.isNotEmpty() && costs.all { it.key.isNotBlank() && it.value > 0 })
        require(trainingTicks == null || trainingTicks > 0)
    }
}
data class IncorporationTicket(
    val eventId: String, val ownerId: Long, val soldiers: Set<SoldierRef>,
    val costs: Map<String, Long>, val readyAt: Long, val completed: Boolean = false,
)

object Incorporation {
    fun enqueue(
        state: PrisonerState, eventId: String, ownerId: Long, soldiers: Set<SoldierRef>,
        now: Long, terms: IncorporationTerms,
    ): PrisonerResult {
        val receipt = PrisonerReceipt("incorporate", listOf(ownerId.toString(), now.toString(), terms.trainingTicks.toString(), terms.costs?.size.toString()) +
            terms.costs.orEmpty().toSortedMap().flatMap { (key, value) -> listOf(key, value.toString()) } + soldiers.sorted().map { it.toString() })
        state.retry(eventId, receipt)?.let { return it }
        if (ownerId <= 0 || now < 0 || soldiers.isEmpty()) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        if (soldiers.any { it !in state.soldiers }) return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (soldiers.any { state.soldiers.getValue(it).ownerGeneralId != ownerId }) return PrisonerResult(state, PrisonerCode.NOT_OWNER)
        if (soldiers.any { state.soldiers.getValue(it).status != SoldierCaptivity.CAPTIVE }) return PrisonerResult(state, PrisonerCode.ACTION_NOT_ALLOWED)
        val costs = terms.costs ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val duration = terms.trainingTicks ?: return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (duration > Long.MAX_VALUE - now) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        val stock = state.stocks[ownerId].orEmpty()
        if (costs.any { (key, amount) -> (stock[key] ?: 0) < amount }) return PrisonerResult(state, PrisonerCode.INSUFFICIENT_STOCK)
        val remaining = stock.toMutableMap()
        costs.forEach { (key, amount) -> remaining[key] = remaining.getValue(key) - amount }
        val ticket = IncorporationTicket(eventId, ownerId, soldiers.toSet(), costs.toMap(), now + duration)
        val next = state.copy(
            stocks = state.stocks + (ownerId to remaining.toMap()),
            soldiers = state.soldiers + soldiers.associateWith { state.soldiers.getValue(it).copy(status = SoldierCaptivity.TRAINING) },
            incorporation = state.incorporation + (eventId to ticket),
        )
        return PrisonerResult(next.remember(eventId, receipt), PrisonerCode.APPLIED)
    }

    /** A resolved success is supplied only by an approved retraining outcome policy. */
    fun complete(state: PrisonerState, eventId: String, ownerId: Long, now: Long, successApproved: Boolean?): PrisonerResult {
        val ticket = state.incorporation[eventId] ?: return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (ownerId != ticket.ownerId) return PrisonerResult(state, PrisonerCode.NOT_OWNER)
        if (ticket.completed) return PrisonerResult(state, PrisonerCode.ALREADY_COMPLETED)
        if (now < ticket.readyAt) return PrisonerResult(state, PrisonerCode.NOT_READY)
        if (successApproved != true) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val rows = ticket.soldiers.associateWith {
            state.soldiers.getValue(it).copy(status = SoldierCaptivity.INCORPORATED, departureEventId = eventId)
        }
        return PrisonerResult(state.copy(soldiers = state.soldiers + rows,
            incorporation = state.incorporation + (eventId to ticket.copy(completed = true))), PrisonerCode.APPLIED)
    }

    fun cancel(state: PrisonerState): PrisonerResult = PrisonerResult(state, PrisonerCode.NOT_DECIDED)
    fun enqueueGeneral(state: PrisonerState, generalId: Long): PrisonerResult =
        PrisonerResult(state, if (generalId in state.generals) PrisonerCode.ACTION_NOT_ALLOWED else PrisonerCode.NOT_FOUND)
}

enum class OfferReply { PENDING, ACCEPTED, REJECTED }
data class RecruitmentOffer(val id: String, val generalId: Long, val managerId: Long, val nationId: Long, val reply: OfferReply = OfferReply.PENDING)

object PrisonerRecruitment {
    /** NPC persuasion remains in the existing PeopleInput path, outside this player-consent seam. */
    fun offer(state: PrisonerState, offerId: String, managerId: Long, generalId: Long, nationId: Long, persuasionSucceeded: Boolean?): PrisonerResult {
        val receipt = PrisonerReceipt("offer", listOf(managerId.toString(), generalId.toString(), nationId.toString()))
        state.retry(offerId, receipt)?.let { return it }
        val general = state.generals[generalId] ?: return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (managerId != general.ownerGeneralId) return PrisonerResult(state, PrisonerCode.NOT_OWNER)
        if (nationId <= 0) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        if (!general.player || persuasionSucceeded == null) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (!persuasionSucceeded || general.status != GeneralCaptivity.CAPTIVE ||
            state.offers.values.any { it.generalId == generalId && it.reply == OfferReply.PENDING && it.managerId == managerId }) return PrisonerResult(state, PrisonerCode.ACTION_NOT_ALLOWED)
        val offer = RecruitmentOffer(offerId, generalId, managerId, nationId)
        return PrisonerResult(state.copy(offers = state.offers + (offerId to offer)).remember(offerId, receipt), PrisonerCode.APPLIED)
    }

    fun reply(state: PrisonerState, offerId: String, actorGeneralId: Long, accept: Boolean): PrisonerResult {
        val offer = state.offers[offerId] ?: return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (actorGeneralId != offer.generalId) return PrisonerResult(state, PrisonerCode.NOT_OWNER)
        val reply = if (accept) OfferReply.ACCEPTED else OfferReply.REJECTED
        if (offer.reply != OfferReply.PENDING) return PrisonerResult(state,
            if (offer.reply == reply) PrisonerCode.ALREADY_COMPLETED else PrisonerCode.INVALID_REQUEST)
        val general = state.generals.getValue(offer.generalId)
        if (general.ownerGeneralId != offer.managerId || general.status != GeneralCaptivity.CAPTIVE) return PrisonerResult(state, PrisonerCode.ACTION_NOT_ALLOWED)
        val next = if (accept) general.copy(nationId = offer.nationId, status = GeneralCaptivity.RECRUITED) else general
        return PrisonerResult(state.copy(generals = state.generals + (general.generalId to next),
            offers = state.offers + (offerId to offer.copy(reply = reply))), PrisonerCode.APPLIED)
    }
}
