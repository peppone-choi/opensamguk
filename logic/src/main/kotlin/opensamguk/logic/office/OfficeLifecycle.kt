package opensamguk.logic.office

enum class OfficeLifecycleReason { HOLDER_MISSING, HOLDER_DEAD, HOLDER_RETIRED, NATION_CHANGED, SEAT_LOST }

data class OfficeLifecycleFacts(
    val livingGeneralIds: Set<Int>,
    val retiredGeneralIds: Set<Int>,
    val nationByGeneralId: Map<Int, Int>,
    val countyNationById: Map<Int, Int>,
    val previousCountyNationById: Map<Int, Int> = emptyMap(),
) {
    init {
        require(retiredGeneralIds.all { it in livingGeneralIds })
        require(nationByGeneralId.values.all { it >= 0 })
    }
}

data class OfficeLifecycleChange(val tenureId: String, val reason: OfficeLifecycleReason, val ended: Boolean)
data class OfficeLifecycleResult(val tenures: List<OfficeTenure>, val changes: List<OfficeLifecycleChange>)

/** Close orphan tenure on political/person events; lost territory retains a nominal claim. */
object OfficeLifecycle {
    fun reconcile(tenures: Collection<OfficeTenure>, facts: OfficeLifecycleFacts, turn: Long): OfficeLifecycleResult {
        require(turn >= 0 && tenures.map { it.id }.distinct().size == tenures.size)
        val changes = mutableListOf<OfficeLifecycleChange>()
        val updated = tenures.sortedBy { it.id }.map { tenure ->
            if (!tenure.isActive) return@map tenure
            val reason = when {
                tenure.holderId !in facts.nationByGeneralId -> OfficeLifecycleReason.HOLDER_MISSING
                tenure.holderId !in facts.livingGeneralIds -> OfficeLifecycleReason.HOLDER_DEAD
                tenure.holderId in facts.retiredGeneralIds -> OfficeLifecycleReason.HOLDER_RETIRED
                facts.nationByGeneralId[tenure.holderId] != tenure.nationId -> OfficeLifecycleReason.NATION_CHANGED
                tenure.seatCountyId != null &&
                    facts.previousCountyNationById[tenure.seatCountyId] == tenure.nationId &&
                    facts.countyNationById[tenure.seatCountyId] != tenure.nationId ->
                    OfficeLifecycleReason.SEAT_LOST
                else -> null
            }
            when (reason) {
                null -> tenure
                OfficeLifecycleReason.SEAT_LOST -> {
                    changes += OfficeLifecycleChange(tenure.id, reason, false)
                    tenure
                }
                else -> {
                    changes += OfficeLifecycleChange(tenure.id, reason, true)
                    OfficeTenureTransitions.end(tenure, turn)
                }
            }
        }
        return OfficeLifecycleResult(updated, changes)
    }
}
