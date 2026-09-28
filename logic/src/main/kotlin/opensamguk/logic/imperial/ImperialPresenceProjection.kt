package opensamguk.logic.imperial

import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef

data class ImperialPresenceBadge(
    val lineCode: String,
    val lineName: String,
    val emperorGeneralId: Int,
    val emperorNode: StrategicNodeRef,
    /** Present only when the emperor is physically at the reference city. */
    val emperorCityId: Int?,
    val courtCityId: Int?,
)

/** The spatial position is authoritative; general.city_id only identifies a possible city at that node. */
object ImperialPresenceProjection {
    fun badges(
        world: ImperialWorldState,
        generalBaseCityIds: Map<Int, Int>,
        positions: Map<Int, GeneralPositionState>,
        cityProvinceById: Map<Int, String>,
    ): List<ImperialPresenceBadge> {
        require(generalBaseCityIds.all { (generalId, cityId) -> generalId > 0 && cityId > 0 })
        return world.houses.asSequence()
            .filter { it.status == ImperialLineStatus.ACTIVE }
            .sortedBy { it.code }
            .map { house ->
                val emperorId = requireNotNull(house.holderGeneralId)
                val referenceCityId = requireNotNull(generalBaseCityIds[emperorId]) {
                    "active emperor has no reference city"
                }
                val provinceId = requireNotNull(cityProvinceById[referenceCityId]) {
                    "active emperor reference city has no province"
                }
                val position = requireNotNull(positions[emperorId]) {
                    "active emperor has no spatial position"
                }
                val emperorCityId = referenceCityId.takeIf {
                    position.battlefield == null && position.node == StrategicNodeRef.LandProvince(provinceId)
                }
                ImperialPresenceBadge(house.code, house.name, emperorId, position.node,
                    emperorCityId, house.courtCityId)
            }
            .toList()
    }
}
