package opensamguk.gameapi.dto

/** 부(府) 화면의 부곡 한 행. */
data class RetinueBugokDto(
    val id: Int,
    val name: String,
    val troops: Int,
    val crewTypeId: Int,
    val crewTypeName: String,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val provisions: Int,
    val provisionMonths: Int,
    val commanderRetainerId: Int?,
)
