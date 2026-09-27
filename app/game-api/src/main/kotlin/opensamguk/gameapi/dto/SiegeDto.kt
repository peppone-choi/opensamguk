package opensamguk.gameapi.dto

/**
 * `/api/sieges?generalId=` 응답. 부드러운 상태는 [SiegesResponse.status] 로 알린다
 * (`READY` · `UNAVAILABLE` · `WRONG_RULE_PROFILE`). 사기는 정수 10000 = 100% 그대로 낸다.
 */
data class SiegePartyDto(val generalId: Int, val name: String?, val nationId: Int, val nationName: String?)

data class SiegePhaseDto(val year: Int, val month: Int, val phase: Int)

data class SiegeDto(
    val countyId: Int,
    val countyName: String?,
    val status: String,
    val endReason: String?,
    val besieger: SiegePartyDto,
    val defenderNationId: Int,
    val defenderNationName: String?,
    val startedAt: SiegePhaseDto,
    /** 누적 순(포위가 유지된 순 경계 수). */
    val turns: Int,
    /** 성 안 군량 — 縣 창고 곡. 창고가 없거나 읽히지 않으면 null. */
    val grain: Long?,
    /** 성 안 사기(0..10000). */
    val morale: Int,
    val garrison: Int,
    /** 민심(city trust, 0..100). */
    val trust: Double,
    /** 縣의 외부 보급 여부(포위 중이면 끊긴다). */
    val countySupplied: Boolean,
    /** 포위 군단 병력과 「당순 완전 급식」 판정. 군단을 읽을 수 없으면 null. */
    val besiegerTroops: Int?,
    val besiegerFed: Boolean?,
    /** 조회한 장수가 포위 지휘관이라 강공·항복 권고를 넣을 수 있는지. */
    val canAct: Boolean,
    /** 지금 항복 권고가 받아들여질 조건(사기·민심 문턱)인지. 결정론이라 미리 알려 준다. */
    val surrenderDemandAccepted: Boolean,
    val timeline: List<Map<String, Any?>>,
)

data class SiegesResponse(val status: String, val sieges: List<SiegeDto> = emptyList())
