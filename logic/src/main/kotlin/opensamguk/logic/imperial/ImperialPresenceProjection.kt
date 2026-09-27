package opensamguk.logic.imperial

data class ImperialPresenceBadge(
    val lineCode: String,
    val lineName: String,
    val emperorGeneralId: Int,
    /** Reference city from general.city_id; it is not the spatial position. */
    val emperorCityId: Int,
    val courtCityId: Int?,
)

/** The emperor's reference city comes from the person, never from the court seat. */
object ImperialPresenceProjection {
    fun badges(
        world: ImperialWorldState,
        generalBaseCityIds: Map<Int, Int>,
    ): List<ImperialPresenceBadge> {
        require(generalBaseCityIds.all { (generalId, cityId) -> generalId > 0 && cityId > 0 })
        return world.houses.asSequence()
            .filter { it.status == ImperialLineStatus.ACTIVE }
            .sortedBy { it.code }
            .map { house ->
                val emperorId = requireNotNull(house.holderGeneralId)
                val emperorCityId = requireNotNull(generalBaseCityIds[emperorId]) {
                    "active emperor has no reference city"
                }
                ImperialPresenceBadge(house.code, house.name, emperorId, emperorCityId, house.courtCityId)
            }
            .toList()
    }
}
