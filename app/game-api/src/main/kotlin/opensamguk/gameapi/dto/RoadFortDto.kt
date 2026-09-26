package opensamguk.gameapi.dto

data class RoadFortDto(
    val id: String,
    val edgeId: String,
    val provinceId: String,
    val row: Int,
    val col: Int,
    val ownerNationId: Int,
    val wall: Int,
    val garrison: Int,
    val besiegerGeneralId: Int?,
    val siegeProgress: Int,
    val canBesiege: Boolean,
)

data class RoadGateDto(
    val edgeId: String,
    val fromProvinceId: String,
    val toProvinceId: String,
    val active: Boolean,
    val buildable: Boolean,
    val historicalRouteIds: List<String>,
    val fortCells: List<opensamguk.logic.world.StrategicFortCell>,
)

data class RoadFortsResponse(
    val status: String,
    val forts: List<RoadFortDto> = emptyList(),
    val gates: List<RoadGateDto> = emptyList(),
    val roadMode: Boolean = false,
)
