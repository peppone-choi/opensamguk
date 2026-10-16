package opensamguk.logic.battle.realtime.economy

/** debitRecordId is an opaque integration seam; it has no R-2 compile dependency. */
data class PopulationSource(val id: String, val owner: String, val initial: Long, val available: Long = initial) {
    init { require(id.isNotBlank() && owner.isNotBlank() && initial >= 0 && available in 0..initial) }
}
data class ReserveBatch(val id: String, val owner: String, val sourceId: String, val debitRecordId: String, val initial: Long, val remaining: Long)
data class PopulationDebit(val sourceId: String, val sourceOwner: String, val reserveBatchId: String?, val debitRecordId: String)
fun EconomyLedger.acquireReserve(id: String, owner: String, sourceId: String, debitRecordId: String, params: EconomyParams) =
    apply(id, key("reserve", owner, sourceId, debitRecordId, params.fingerprint())) { s ->
        params.confirmed(); ensure(params.reserveAcquisitionEnabled == true, EconomyCode.NOT_DECIDED)
        val amount = params.positive(params.reserveBatchSize); identifier(debitRecordId)
        val source = s.population.required(sourceId); ensure(source.owner == owner, EconomyCode.OWNER_CHANGED)
        ensure(s.reserves.values.none { it.debitRecordId == debitRecordId } &&
            s.orders.values.none { it.populationDebit?.debitRecordId == debitRecordId || debitRecordId in it.completedDebitIds }, EconomyCode.DUPLICATE_ID)
        ensure(source.available >= amount, EconomyCode.INSUFFICIENT_POPULATION)
        Change(s.copy(population = s.population + (sourceId to source.copy(available = source.available - amount)),
            reserves = s.reserves + (id to ReserveBatch(id, owner, sourceId, debitRecordId, amount, amount))))
    }
/** Caller supplies a previously accepted ownership fact; this never writes a campaign record. */
fun EconomyLedger.observeSourceOwner(id: String, sourceId: String, owner: String) = apply(id, key("sourceOwner", sourceId, owner)) { s ->
    identifier(owner); val source = s.population.required(sourceId)
    Change(s.copy(population = s.population + (sourceId to source.copy(owner = owner))))
}
internal fun EconomyState.reservePerson(owner: String, sourceId: String, connected: Boolean, debitId: String): Pair<EconomyState, PopulationDebit> {
    if (!connected) {
        val batch = reserves.toSortedMap().values.firstOrNull { it.owner == owner && it.remaining > 0 }
            ?: throw Rejected(EconomyCode.INSUFFICIENT_RESERVE)
        return copy(reserves = reserves + (batch.id to batch.copy(remaining = batch.remaining - 1))) to
            PopulationDebit(batch.sourceId, owner, batch.id, batch.debitRecordId)
    }
    val source = population.required(sourceId); ensure(source.owner == owner, EconomyCode.OWNER_CHANGED)
    ensure(source.available > 0, EconomyCode.INSUFFICIENT_POPULATION)
    return copy(population = population + (sourceId to source.copy(available = source.available - 1))) to
        PopulationDebit(sourceId, owner, null, debitId)
}
internal fun EconomyState.refundPerson(debit: PopulationDebit): EconomyState {
    if (debit.reserveBatchId != null) {
        val batch = reserves.required(debit.reserveBatchId)
        return copy(reserves = reserves + (batch.id to batch.copy(remaining = Math.incrementExact(batch.remaining))))
    }
    val source = population.required(debit.sourceId)
    ensure(source.owner == debit.sourceOwner, EconomyCode.NOT_DECIDED)
    return copy(population = population + (source.id to source.copy(available = Math.incrementExact(source.available))))
}
