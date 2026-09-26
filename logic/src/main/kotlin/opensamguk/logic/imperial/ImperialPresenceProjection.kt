package opensamguk.logic.imperial

data class ImperialPresenceBadge(
    val lineCode: String,
    val lineName: String,
    val emperorGeneralId: Int,
    val emperorCityId: Int,
    val courtCityId: Int?,
)

/** The emperor's mapped position comes from the person, never from the court seat. */
object ImperialPresenceProjection {
    fun badges(
        world: ImperialWorldState,
        generalCityIds: Map<Int, Int>,
    ): List<ImperialPresenceBadge> {
        require(generalCityIds.all { (generalId, cityId) -> generalId > 0 && cityId > 0 })
        return world.houses.asSequence()
            .filter { it.status == ImperialLineStatus.ACTIVE }
            .sortedBy { it.code }
            .map { house ->
                val emperorId = requireNotNull(house.holderGeneralId)
                val emperorCityId = requireNotNull(generalCityIds[emperorId]) {
                    "active emperor has no mapped physical position"
                }
                ImperialPresenceBadge(house.code, house.name, emperorId, emperorCityId, house.courtCityId)
            }
            .toList()
    }
}
