package opensamguk.logic.input

import opensamguk.logic.world.StrategicNodeRef

sealed interface ReturnDestination {
    data class Ready(val node: StrategicNodeRef.LandProvince) : ReturnDestination
    data class Rejected(val reason: TravelFailure) : ReturnDestination
}

/** The dispatch county is the stable home; `general.city_id` is only a moving reference city. */
object TravelReturn {
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
