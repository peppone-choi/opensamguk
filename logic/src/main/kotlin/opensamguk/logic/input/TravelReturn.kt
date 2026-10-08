package opensamguk.logic.input

import opensamguk.logic.world.*

sealed interface ReturnDestination {
    data class Ready(val node: StrategicNodeRef.LandProvince) : ReturnDestination
    data class Rejected(val reason: TravelFailure) : ReturnDestination
}

/** The dispatch county is the stable home; `general.city_id` is only a moving reference city. */
object TravelReturn {
    /** Preserve the selected route's exact first executable edge and its immutable pins. */
    fun firstStep(path: ResolvedLandMarchPath, metrics: LandMarchMetricSnapshot): ResolvedLandMarchPath {
        require(path.edgeIds.isNotEmpty() && path.metricHash == metrics.contentHash)
        val nodes = path.nodeKeys.take(2)
        val ids = path.edgeIds.take(1)
        val modes = path.modes.take(1)
        val cost = metrics.edgesById.getValue(ids.single()).costMm
        return ResolvedLandMarchPath(nodes, ids, modes, cost, path.capacity,
            path.topologyRevision, path.topologyHash, path.metricHash,
            landMarchPathHash(nodes, ids, modes, cost, path.capacity,
                path.topologyRevision, path.topologyHash, path.metricHash))
    }

    fun resolve(actorMeta: Map<String, Any?>, actorNationId: Int,
        landNodeOfCity: (Int) -> StrategicNodeRef?): ReturnDestination {
        val assignment = try { CountyAssignment.read(actorMeta) }
            catch (_: IllegalArgumentException) { return ReturnDestination.Rejected(TravelFailure.STATE_UNAVAILABLE) }
            ?: return ReturnDestination.Rejected(TravelFailure.NO_RETURN_ASSIGNMENT)
        if (assignment.nationId != actorNationId)
            return ReturnDestination.Rejected(TravelFailure.NO_RETURN_ASSIGNMENT)
        val node = landNodeOfCity(assignment.countyId) as? StrategicNodeRef.LandProvince
            ?: return ReturnDestination.Rejected(TravelFailure.INVALID_DESTINATION)
        return ReturnDestination.Ready(node)
    }
}
