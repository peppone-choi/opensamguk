package opensamguk.logic.prisoner

/** No engine dependency: settlement supplies the resolved captor and original soldier identities. */
enum class PrisonerCode {
    APPLIED, ALREADY_COMPLETED, NOT_DECIDED, NOT_OWNER, NOT_FOUND, INVALID_REQUEST,
    ACTION_NOT_ALLOWED, INSUFFICIENT_STOCK, NOT_READY, RETRY_WAIT,
}

data class PrisonerResult(val state: PrisonerState, val code: PrisonerCode)
enum class SoldierCaptivity { CAPTIVE, TRAINING, INCORPORATED, RELEASED, EXCHANGED }
enum class GeneralCaptivity { CAPTIVE, RECRUITED, ESCAPED }

data class PrisonerSource(
    val battleId: String,
    val sourceKey: String,
    val revision: Long,
    val originNationId: Long,
    val troopType: String,
) {
    init {
        require(battleId.isNotBlank() && sourceKey.isNotBlank() && troopType.isNotBlank())
        require(revision >= 0 && originNationId >= 0)
    }
}

data class SoldierRef(val battleId: String, val soldierId: Long) : Comparable<SoldierRef> {
    init { require(battleId.isNotBlank() && soldierId > 0) }
    override fun compareTo(other: SoldierRef): Int =
        compareValuesBy(this, other, SoldierRef::battleId, SoldierRef::soldierId)
}

data class SoldierPrisoner(
    val ref: SoldierRef,
    val source: PrisonerSource,
    val captureEventId: String,
    val ownerGeneralId: Long,
    val status: SoldierCaptivity = SoldierCaptivity.CAPTIVE,
    val departureEventId: String? = null,
)

data class GeneralPrisoner(
    val generalId: Long,
    val originNationId: Long,
    val nationId: Long,
    val ownerGeneralId: Long,
    val player: Boolean,
    val captureEventId: String,
    val status: GeneralCaptivity = GeneralCaptivity.CAPTIVE,
    val guardLevel: Long = 0,
    val escapeDifficulty: Long = 0,
    val retryAvailableAt: Long = 0,
    val attempts: Long = 0,
)

data class PrisonerGroupKey(val ownerGeneralId: Long, val originNationId: Long, val troopType: String)
data class PrisonerSourceCount(val source: PrisonerSource, val soldiers: List<SoldierRef>) {
    val count: Int get() = soldiers.size
}
data class PrisonerGroup(val key: PrisonerGroupKey, val sources: List<PrisonerSourceCount>) {
    val count: Int get() = sources.sumOf { it.count }
}

/** Receipts bind an event ID to its complete command, rejecting changed-payload retries. */
data class PrisonerReceipt(val kind: String, val payload: List<String>)
data class PrisonerState(
    val soldiers: Map<SoldierRef, SoldierPrisoner> = emptyMap(),
    val generals: Map<Long, GeneralPrisoner> = emptyMap(),
    val receipts: Map<String, PrisonerReceipt> = emptyMap(),
    val stocks: Map<Long, Map<String, Long>> = emptyMap(),
    val incorporation: Map<String, IncorporationTicket> = emptyMap(),
    val offers: Map<String, RecruitmentOffer> = emptyMap(),
) {
    init { require(stocks.all { (owner, resources) -> owner > 0 && resources.all { it.key.isNotBlank() && it.value >= 0 } }) }

    fun groups(): List<PrisonerGroup> = soldiers.values
        .filter { it.status == SoldierCaptivity.CAPTIVE || it.status == SoldierCaptivity.TRAINING }
        .groupBy { PrisonerGroupKey(it.ownerGeneralId, it.source.originNationId, it.source.troopType) }
        .entries.sortedWith(compareBy({ it.key.ownerGeneralId }, { it.key.originNationId }, { it.key.troopType }))
        .map { (key, records) ->
            PrisonerGroup(key, records.groupBy { it.source }.entries
                .sortedWith(compareBy({ it.key.battleId }, { it.key.sourceKey }, { it.key.revision }))
                .map { (source, rows) -> PrisonerSourceCount(source, rows.map { it.ref }.sorted()) })
        }
}

internal fun PrisonerState.retry(eventId: String, receipt: PrisonerReceipt): PrisonerResult? {
    if (eventId.isBlank()) return PrisonerResult(this, PrisonerCode.INVALID_REQUEST)
    val prior = receipts[eventId] ?: return null
    return PrisonerResult(this, if (prior == receipt) PrisonerCode.ALREADY_COMPLETED else PrisonerCode.INVALID_REQUEST)
}
internal fun PrisonerState.remember(eventId: String, receipt: PrisonerReceipt): PrisonerState =
    copy(receipts = receipts + (eventId to receipt))

object PrisonerRecords {
    fun captureSoldiers(
        state: PrisonerState, eventId: String, source: PrisonerSource,
        soldierIds: Set<Long>, surrenderReceiverId: Long?,
    ): PrisonerResult {
        val receipt = PrisonerReceipt("capture-soldiers", listOf(source.battleId, source.sourceKey, source.revision.toString(), source.originNationId.toString(), source.troopType, surrenderReceiverId.toString()) + soldierIds.sorted().map { it.toString() })
        state.retry(eventId, receipt)?.let { return it }
        if (surrenderReceiverId == null) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (surrenderReceiverId <= 0 || soldierIds.isEmpty() || soldierIds.any { it <= 0 }) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        val refs = soldierIds.map { SoldierRef(source.battleId, it) }
        if (state.soldiers.values.any { it.source.battleId == source.battleId && it.source.sourceKey == source.sourceKey && it.source != source }) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        val existing = refs.mapNotNull { state.soldiers[it] }
        if (existing.any { it.status == SoldierCaptivity.CAPTIVE }) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        // Recapture history and disposition carry-over are not decided; preserve the entire ledger.
        if (existing.isNotEmpty()) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        val rows = refs.associateWith { SoldierPrisoner(it, source, eventId, surrenderReceiverId) }
        return PrisonerResult(state.copy(soldiers = state.soldiers + rows).remember(eventId, receipt), PrisonerCode.APPLIED)
    }

    fun captureGeneral(
        state: PrisonerState, eventId: String, generalId: Long, originNationId: Long,
        player: Boolean, surrenderReceiverId: Long?,
    ): PrisonerResult {
        val receipt = PrisonerReceipt("capture-general", listOf(generalId, originNationId, player, surrenderReceiverId).map { it.toString() })
        state.retry(eventId, receipt)?.let { return it }
        if (surrenderReceiverId == null) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (generalId <= 0 || originNationId < 0 || surrenderReceiverId <= 0 || generalId == surrenderReceiverId) {
            return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        }
        state.generals[generalId]?.let {
            return PrisonerResult(state, if (it.status == GeneralCaptivity.CAPTIVE) PrisonerCode.INVALID_REQUEST else PrisonerCode.NOT_DECIDED)
        }
        val row = GeneralPrisoner(generalId, originNationId, originNationId, surrenderReceiverId, player, eventId)
        return PrisonerResult(state.copy(generals = state.generals + (generalId to row)).remember(eventId, receipt), PrisonerCode.APPLIED)
    }

    /** Release/exchange conditions belong to a future boundary; no implicit disposition. */
    fun departSoldiers(
        state: PrisonerState, eventId: String, ownerId: Long, soldiers: Set<SoldierRef>,
        disposition: SoldierCaptivity, conditionsApproved: Boolean?,
    ): PrisonerResult {
        val receipt = PrisonerReceipt("depart", listOf(ownerId.toString(), disposition.name) + soldiers.sorted().map { it.toString() })
        state.retry(eventId, receipt)?.let { return it }
        if (disposition != SoldierCaptivity.RELEASED && disposition != SoldierCaptivity.EXCHANGED || soldiers.isEmpty()) return PrisonerResult(state, PrisonerCode.INVALID_REQUEST)
        if (soldiers.any { it !in state.soldiers }) return PrisonerResult(state, PrisonerCode.NOT_FOUND)
        if (soldiers.any { state.soldiers.getValue(it).ownerGeneralId != ownerId }) return PrisonerResult(state, PrisonerCode.NOT_OWNER)
        if (conditionsApproved == null) return PrisonerResult(state, PrisonerCode.NOT_DECIDED)
        if (!conditionsApproved || soldiers.any { state.soldiers.getValue(it).status != SoldierCaptivity.CAPTIVE }) return PrisonerResult(state, PrisonerCode.ACTION_NOT_ALLOWED)
        val updated = soldiers.associateWith { state.soldiers.getValue(it).copy(status = disposition, departureEventId = eventId) }
        return PrisonerResult(state.copy(soldiers = state.soldiers + updated).remember(eventId, receipt), PrisonerCode.APPLIED)
    }
}
