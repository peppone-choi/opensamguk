package opensamguk.infra.persistence

/** V61 `siege` 한 행 — 엔진 `Siege` 의 flush 표현. [timelineJson] 은 JSON 배열 문자열이다. */
data class SiegeRow(
    val countyId: Int,
    val status: String,
    val besiegerGeneralId: Int,
    val besiegerOwnerGeneralId: Int,
    val besiegerOrderId: String,
    val besiegerNationId: Int,
    val defenderNationId: Int,
    val approachProvinceId: String,
    val startedYear: Int,
    val startedMonth: Int,
    val startedPhase: Int,
    val settledYear: Int?,
    val settledMonth: Int?,
    val settledPhase: Int?,
    val turns: Int,
    val morale: Int,
    val garrison: Int,
    val endReason: String?,
    val timelineJson: String,
)
