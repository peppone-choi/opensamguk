package opensamguk.gameapi.dto

/** Phase 4X-B `/api/operations` (spec v4.1 §6). 진척은 이정표 4개(불리언)가 1차, `milestoneDisplayPct` 는 파생 표시값. */
data class OperationDateDto(val year: Int, val month: Int, val phase: Int)

data class OperationUnitDto(
    val id: Int,
    val generalId: Int,
    val name: String,
    val role: String,
    val roleLabel: String,
    val crew: Int,
    val crewTypeName: String,
    val bugokId: Int?,
    val bugokTroops: Int?,
    val cityId: Int,
    val cityName: String,
    val picture: String?,
    val imageServer: Int,
)

data class OperationMilestonesDto(val departed: Boolean, val arrived: Boolean, val supplied: Boolean, val objective: Boolean)

data class OperationDto(
    val id: Int,
    val kind: String,
    val kindLabel: String,
    val title: String,
    val fallbackText: String?,
    val target: OperationTargetDto,
    val status: String,
    val statusLabel: String,
    val closedReason: String?,
    val declaredAt: OperationDateDto,
    val deadline: OperationDateDto,
    /** 진행 중(declared/active)에서만, 종료 상태는 null(P3). */
    val remainingMonths: Int?,
    val milestones: OperationMilestonesDto,
    /** 파생 표시값(이정표 수 × 25) — 1차 표기는 「이정표 k/4」. */
    val milestoneDisplayPct: Int,
    val units: List<OperationUnitDto>,
    val declaredBy: OperationPersonDto?,
    val boardPostIds: List<Int>,
)

data class OperationTargetDto(val cityId: Int, val name: String)
data class OperationPersonDto(val generalId: Int, val name: String)
data class OperationKindDto(val kind: String, val label: String, val declarable: Boolean, val reason: String?)

data class OperationRulesDto(
    val maxActivePerNation: Int,
    val minDeadlineMonths: Int,
    val maxDeadlineMonths: Int,
    val maxUnits: Int,
    val failAtmosLoss: Int,
    val milestoneDisplayPct: Int,
    val kinds: List<OperationKindDto>,
    val roles: List<Map<String, String>>,
    val provisional: Boolean,
)

data class OperationsResponse(
    val nationId: Int,
    /** 엔진 `SecretPermission` 과 같은 원천(`SecretPermissionReader`, checkSecretLimit=true 라 belong 분기에서 ±1 차이 가능). */
    val myPermission: Int,
    val myGeneralId: Int,
    val operations: List<OperationDto>,
    val rules: OperationRulesDto,
)

data class OperationDetailResponse(
    val operation: OperationDto,
    val boardPosts: List<OperationBoardPostDto>,
)

data class OperationBoardPostDto(val id: Int, val title: String, val authorName: String, val createdAt: java.time.Instant)
