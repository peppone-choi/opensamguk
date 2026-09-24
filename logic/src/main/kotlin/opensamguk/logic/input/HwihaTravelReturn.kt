package opensamguk.logic.input

import opensamguk.logic.world.StrategicNodeRef

sealed interface HwihaReturnDestination {
    data class Ready(val node: StrategicNodeRef.LandProvince) : HwihaReturnDestination
    data class Rejected(val reason: HwihaTravelFailure) : HwihaReturnDestination
}

/** The dispatch county is the stable home; `general.city_id` is only a moving reference city. */
object HwihaTravelReturn {
    fun resolve(actorMeta: Map<String, Any?>, actorNationId: Int,
        landNodeOfCity: (Int) -> StrategicNodeRef?): HwihaReturnDestination {
        val assignment = try { HwihaCountyAssignment.read(actorMeta) }
            catch (_: IllegalArgumentException) { return HwihaReturnDestination.Rejected(HwihaTravelFailure.STATE_UNAVAILABLE) }
            ?: return HwihaReturnDestination.Rejected(HwihaTravelFailure.NO_RETURN_ASSIGNMENT)
        if (assignment.nationId != actorNationId)
            return HwihaReturnDestination.Rejected(HwihaTravelFailure.NO_RETURN_ASSIGNMENT)
        val node = landNodeOfCity(assignment.countyId) as? StrategicNodeRef.LandProvince
            ?: return HwihaReturnDestination.Rejected(HwihaTravelFailure.INVALID_DESTINATION)
        return HwihaReturnDestination.Ready(node)
    }
}
