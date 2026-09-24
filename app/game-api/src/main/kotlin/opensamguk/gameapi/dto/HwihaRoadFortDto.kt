package opensamguk.gameapi.dto

data class HwihaRoadFortDto(
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

data class HwihaRoadGateDto(
    val edgeId: String,
    val fromProvinceId: String,
    val toProvinceId: String,
    val active: Boolean,
    val buildable: Boolean,
    val historicalRouteIds: List<String>,
    val fortCells: List<opensamguk.logic.world.StrategicFortCell>,
)

data class HwihaRoadFortsResponse(
    val status: String,
    val forts: List<HwihaRoadFortDto> = emptyList(),
    val gates: List<HwihaRoadGateDto> = emptyList(),
    val roadMode: Boolean = false,
)
