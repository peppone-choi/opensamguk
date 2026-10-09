package opensamguk.logic.input

import opensamguk.logic.world.*

data class PersonalForcedMarchPreview(val distanceMm: Long, val estimatedTurns: Long,
    val forcedFatigueDelta: Int, val forcedMoraleDelta: Int, val afterFatigue: Int, val afterMorale: Int)

/** Personal-only route limits and costs; shared army/convoy/RETURN marching is unchanged. */
object PersonalForcedMarchPolicy {
    fun estimatedTurns(costMm: Long): Long {
        require(costMm >= 0)
        return costMm / ForcedMarchTempo.budgetMm + if (costMm % ForcedMarchTempo.budgetMm == 0L) 0 else 1
    }

    fun routeFailure(path: ResolvedLandMarchPath, metrics: LandMarchMetricSnapshot): TravelFailure? {
        require(path.metricHash == metrics.contentHash)
        var distance = 0L
        for (id in path.edgeIds) {
            val leg = metrics.edgesById.getValue(id).distanceMm
            if (leg > ForcedMarchTempo.maxRouteDistanceMm - distance) return TravelFailure.FORCED_ROUTE_TOO_LONG
            distance += leg
        }
        return if (estimatedTurns(path.totalCostMm) > ForcedMarchTempo.maxEstimatedTurns)
            TravelFailure.FORCED_DURATION_EXCEEDED else null
    }

    fun assess(route: LandMarchPathResult, direct: ResolvedLandMarchPath?,
        metrics: LandMarchMetricSnapshot, condition: PersonalTravelCondition): TravelAssessment {
        val paths = listOfNotNull((route as? LandMarchPathResult.Resolved)?.path, direct).distinctBy { it.pathHash }
        val legal = paths.filter { routeFailure(it, metrics) == null }
        if (legal.isEmpty()) return TravelAssessment.Rejected(paths.firstOrNull()?.let { routeFailure(it, metrics) }
            ?: TravelFailure.NO_ROUTE)
        val payable = legal.mapNotNull { path ->
            val distance = path.edgeIds.fold(0L) { total, id -> Math.addExact(total, metrics.edgesById.getValue(id).distanceMm) }
            val cost = condition.forcedCost(distance)
            if (!condition.canPay(cost)) return@mapNotNull null
            TravelAssessment.Eligible(path, PersonalForcedMarchPreview(distance, estimatedTurns(path.totalCostMm),
                cost.fatigueGain.toInt(), -cost.moraleLoss.toInt(), condition.fatigue + cost.fatigueGain.toInt(),
                condition.morale - cost.moraleLoss.toInt()))
        }
        return payable.minWithOrNull(compareBy<TravelAssessment.Eligible> { it.forcedPreview!!.estimatedTurns }
            .thenBy { it.path.totalCostMm }.thenComparator { first, second ->
                val a = first.path.edgeIds; val b = second.path.edgeIds
                (a.indices.take(minOf(a.size, b.size)).firstNotNullOfOrNull { index ->
                    a[index].compareTo(b[index]).takeIf { it != 0 }
                }) ?: a.size.compareTo(b.size)
            }) ?: TravelAssessment.Rejected(TravelFailure.FORCED_MARCH_EXHAUSTED)
    }
}
