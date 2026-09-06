package opensamguk.infra.persistence

/** Phase 4X-B 작전 flush 행(step-8h). 엔진 `Operation`/`OperationUnit` 모양의 infra 운반체(RetainerRow 와 같은 위치). */
data class OperationRow(
    val id: Int,
    val nationId: Int,
    val kind: String,
    val targetCityId: Int,
    val title: String,
    val fallbackText: String?,
    val declaredByGeneralId: Int?,
    val declaredYear: Int,
    val declaredMonth: Int,
    val declaredPhase: Int,
    val deadlineYear: Int,
    val deadlineMonth: Int,
    val deadlinePhase: Int,
    val status: String,
    val departed: Boolean,
    val arrived: Boolean,
    val supplied: Boolean,
    val objective: Boolean,
    val closedReason: String?,
)

data class OperationUnitRow(
    val id: Int,
    val operationId: Int,
    val generalId: Int,
    val bugokId: Int?,
    val role: String,
    val joinedCityId: Int,
    val joinedYear: Int,
    val joinedMonth: Int,
    val joinedPhase: Int,
)
