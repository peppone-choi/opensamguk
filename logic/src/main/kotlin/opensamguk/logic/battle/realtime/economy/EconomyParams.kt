package opensamguk.logic.battle.realtime.economy

/** Fixtures may inject CONFIRMED values; no product defaults or data loading live here. */
enum class ParameterStatus { PROPOSED, CONFIRMED }
enum class EconomyMode { GENERAL_TURN, REALTIME }
data class EconomyParams(
    val status: ParameterStatus = ParameterStatus.PROPOSED,
    val carrierCapacity: Long? = null,
    val gatherAmount: Long? = null,
    val farmWorkers: Long? = null,
    val farmTicks: Long? = null,
    val farmYield: Long? = null,
    val farmReseedTimber: Long? = null,
    val collectionKinds: Set<String>? = null,
    val collectionRadius: Long? = null,
    val depositTicks: Long? = null,
    val dropRetainedNumerator: Long? = null,
    val dropRetainedDenominator: Long? = null,
    val transferEnabled: Boolean? = null,
    val reserveAcquisitionEnabled: Boolean? = null,
    val reserveBatchSize: Long? = null,
    val housingPerBuilding: Long? = null,
    val reserveHousingDuringProduction: Boolean? = null,
    val productionTurnCost: Long? = null,
    val cancelTurnCost: Long? = null,
    val routeTurnCost: Long? = null,
    val routeAmount: ResourceBundle? = null,
    val routeLegTicks: Long? = null,
)

internal fun EconomyParams.fingerprint(): String = key(status, carrierCapacity, gatherAmount, farmWorkers, farmTicks, farmYield, farmReseedTimber,
    collectionKinds?.sorted(), collectionRadius, depositTicks, dropRetainedNumerator, dropRetainedDenominator,
    transferEnabled, reserveAcquisitionEnabled, reserveBatchSize, housingPerBuilding, reserveHousingDuringProduction,
    productionTurnCost, cancelTurnCost, routeTurnCost, routeAmount, routeLegTicks)
internal fun EconomyParams.confirmed() { ensure(status == ParameterStatus.CONFIRMED, EconomyCode.NOT_DECIDED) }
internal fun EconomyParams.positive(value: Long?): Long {
    confirmed()
    ensure(value != null, EconomyCode.NOT_DECIDED)
    ensure(value!! > 0, EconomyCode.INVALID_INPUT)
    return value
}
internal fun EconomyParams.turns(mode: EconomyMode, value: Long?): Long {
    if (mode == EconomyMode.REALTIME) return 0
    confirmed()
    ensure(value != null, EconomyCode.NOT_DECIDED)
    ensure(value!! >= 0, EconomyCode.INVALID_INPUT)
    return value
}
