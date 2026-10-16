package opensamguk.logic.battle.realtime

import java.util.Collections

enum class TacticalV2PopulationSource { HOME_CITY, RESERVE }

/** References only: the economy package verifies the actual debit and building ownership. */
data class TacticalV2ProductionDebit(
    val resourceLedgerId: String,
    val populationSource: TacticalV2PopulationSource,
    val buildingOwnerGeneralId: Int,
    val buildingOwnerSide: BattleSide,
) {
    init {
        require(resourceLedgerId.isNotBlank())
        require(buildingOwnerGeneralId > 0)
    }
}

data class TacticalV2SourceRegistration(
    val source: TacticalV2UnitSource,
    val idempotencyKey: String,
    val soldierIds: LongRange,
    val productionDebit: TacticalV2ProductionDebit?,
    val arrivalEventId: String?,
)

/** Immutable registration plus accounting; no economy debit, arrival policy or combat activation. */
class TacticalV2SourceRegistry private constructor(
    val ledger: TacticalV2TroopLedger,
    soldierIds: Map<TacticalV2SourceKey, LongRange>,
    registrations: Map<String, TacticalV2SourceRegistration>,
    val maximumSoldierId: Long,
) {
    val soldierIds: Map<TacticalV2SourceKey, LongRange> =
        Collections.unmodifiableMap(LinkedHashMap(soldierIds))
    val registrations: Map<String, TacticalV2SourceRegistration> =
        Collections.unmodifiableMap(LinkedHashMap(registrations))

    /** Losses never free IDs. Only balances may change outside registration. */
    fun withLedger(ledger: TacticalV2TroopLedger): TacticalV2SourceRegistry {
        require(ledger.sources == this.ledger.sources) { "registered sources changed" }
        return TacticalV2SourceRegistry(ledger, soldierIds, registrations, maximumSoldierId)
    }

    /** Replay returns this snapshot and its original receipt, including after intervening losses. */
    fun addSource(
        source: TacticalV2UnitSource,
        idempotencyKey: String,
        productionDebit: TacticalV2ProductionDebit? = null,
        arrivalEventId: String? = null,
    ): TacticalV2SourceRegistry {
        require(idempotencyKey.isNotBlank())
        val previous = registrations[idempotencyKey]
        if (previous != null) {
            require(previous.source == source && previous.productionDebit == productionDebit &&
                previous.arrivalEventId == arrivalEventId) { "idempotency key reused with different content" }
            return this
        }
        when (source.key.kind) {
            TacticalV2SourceKind.PRODUCED -> {
                require(productionDebit != null && arrivalEventId == null)
                require(productionDebit.buildingOwnerGeneralId == source.ownerGeneralId &&
                    productionDebit.buildingOwnerSide == source.side) { "production owner mismatch" }
            }
            TacticalV2SourceKind.REINFORCEMENT ->
                require(productionDebit == null && arrivalEventId == source.key.sourceId)
            else -> throw IllegalArgumentException("dynamic source must be produced or reinforcement")
        }
        val nextLedger = ledger.addSource(source)
        val lastId = Math.addExact(maximumSoldierId, source.initialTroops.toLong())
        val ids = Math.addExact(maximumSoldierId, 1L)..lastId
        val registration = TacticalV2SourceRegistration(source, idempotencyKey, ids,
            productionDebit, arrivalEventId)
        return TacticalV2SourceRegistry(nextLedger, soldierIds + (source.key to ids),
            registrations + (idempotencyKey to registration), lastId)
    }

    companion object {
        /** Seed in canonical source order, counting initial troops even when already lost. */
        fun fromLedger(ledger: TacticalV2TroopLedger): TacticalV2SourceRegistry {
            var maximumId = 0L
            val ids = ledger.sources.associate { source ->
                val firstId = Math.addExact(maximumId, 1L)
                maximumId = Math.addExact(maximumId, source.initialTroops.toLong())
                source.key to (firstId..maximumId)
            }
            return TacticalV2SourceRegistry(ledger, ids, emptyMap(), maximumId)
        }
    }
}
