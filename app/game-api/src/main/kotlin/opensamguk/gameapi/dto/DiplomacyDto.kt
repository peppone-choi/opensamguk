package opensamguk.gameapi.dto

/** 외교 관계 응답 */
data class DiplomacyResponse(
    val srcNationId: Int,
    val destNationId: Int,
    val stateCode: Int,
    val term: Int,
)

/** 외교 행렬 응답 (nation별 관계 목록) */
data class DiplomacyMatrixResponse(
    val nationId: Int,
    val relations: List<DiplomacyResponse>,
)
