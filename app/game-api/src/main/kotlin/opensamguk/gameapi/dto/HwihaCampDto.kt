package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 휘하 조회 네 경로의 응답(`/api/hwiha/yuedan` · `warehouses` · `county/{cityId}` · `retinue`).
 *
 * 부드러운 상태는 HTTP 오류가 아니라 [status] 로 알린다:
 * `READY` · `NOT_ASSESSED`(월단평 전) · `UNAVAILABLE`(월드·원장을 읽지 못함) · `WRONG_RULE_PROFILE`(휘하 규칙 아님).
 */
data class HwihaYuedanSelf(val generalId: Int, val renown: Int?, val retinueCost: Int, val overCapacity: Boolean)

data class HwihaYuedanRow(
    val rank: Int,
    val generalId: Int,
    val name: String,
    val nationId: Int,
    val nationName: String?,
    val nationColor: String?,
    val renown: Int,
)

data class HwihaYuedanResponse(
    val status: String,
    val stamp: String? = null,
    val self: HwihaYuedanSelf? = null,
    val ranking: List<HwihaYuedanRow> = emptyList(),
)

data class HwihaStockDto(val money: Long, val grain: Long, val iron: Long, val timber: Long, val horses: Long)

data class HwihaWarehouseDto(
    val cityId: Int,
    val name: String,
    val commanderyName: String?,
    // Kotlin 모듈이 없어 Jackson 이 `isCapital()` 을 「capital」로 부른다 — 저장소 관례대로 이름을 못박는다.
    @get:JsonProperty("isCapital")
    val isCapital: Boolean,
    val supplied: Boolean,
    val stock: HwihaStockDto,
)

data class HwihaWarehousesResponse(
    val status: String,
    val warehouses: List<HwihaWarehouseDto> = emptyList(),
    /** 창고 meta 가 계약과 달라 읽지 못한 縣 수. 창고가 아예 없는 縣은 세지 않는다. */
    val invalidCount: Int = 0,
)

/**
 * 현 특산 한 줄.
 *
 * [ledgerMonthly] 는 `hwiha-resource-production-v1` 원장의 설계 산출량이다. [monthly] 는 엔진이 실제로
 * 이 縣 창고에 매달 넣는 양이다 — 지금 월 세입(`HwihaMonthlyCountyIncome`)은 산지 몫을 넣지 않으므로
 * `null` 이다. 배선되면 두 값이 같아진다.
 */
data class HwihaSpecialtyDto(val resource: String, val label: String, val monthly: Long?, val ledgerMonthly: Long)

data class HwihaCountyResponse(
    val status: String,
    val cityId: Int,
    val name: String,
    val specialties: List<HwihaSpecialtyDto> = emptyList(),
)

data class HwihaFiveStatsDto(val leadership: Int, val strength: Int, val intel: Int, val politics: Int, val charm: Int)

data class HwihaAptitudesDto(val command: Int, val administration: Int, val strategy: Int, val envoy: Int)

/**
 * 인물 결속 한 건. 지금은 향당(`HYANGDANG`)만 낸다.
 *
 * [nativeCountyName] 은 한글 우선 표기(「패국 초현」) — 활성 세계 판의 城 표(`han-world-v3.json`
 * `meta.displayName`)에서 한 城으로 정확히 풀릴 때만 채우고, 아니면 `null` 이다(지어내지 않는다).
 * [nativeCountyHanja] 는 본관 원장의 郡·縣 한자(「沛國 譙」)이고 항상 있다.
 */
data class HwihaBondDto(
    val kind: String,
    val label: String,
    val nativeCountyName: String?,
    val nativeCountyHanja: String,
    val sameAsLord: Boolean,
)

data class HwihaPersonCardDto(
    val retainerId: Int,
    val generalId: Int?,
    val name: String,
    val picture: String?,
    val imageServer: Int,
    val loyalty: Int,
    val roleLabel: String?,
    val taskLabel: String?,
    val stats: HwihaFiveStatsDto?,
    val cost: Int?,
    val aptitudes: HwihaAptitudesDto?,
    val bonds: List<HwihaBondDto>,
    val departureOrder: Int?,
)

data class HwihaRetinueResponse(
    val status: String,
    val renown: Int? = null,
    val costSum: Int = 0,
    val overCapacity: Boolean = false,
    val people: List<HwihaPersonCardDto> = emptyList(),
    val units: List<RetinueBugokDto> = emptyList(),
)
