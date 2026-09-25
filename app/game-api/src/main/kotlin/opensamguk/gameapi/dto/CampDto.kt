package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 휘하 조회 경로의 응답(`/api/hwiha/yuedan` · `warehouses` · `county/{cityId}` · `retinue` · `last-turns`).
 *
 * 부드러운 상태는 HTTP 오류가 아니라 [status] 로 알린다:
 * `READY` · `NOT_ASSESSED`(월단평 전) · `UNAVAILABLE`(월드·원장을 읽지 못함) · `WRONG_RULE_PROFILE`(휘하 규칙 아님).
 */
data class HwihaYuedanSelf(val generalId: Int, val renown: Int?, val retinueCost: Int?, val overCapacity: Boolean)

/**
 * 월단평 사유 한 줄 — 마지막 월단평이 그 장수에게 적용한 사건 **종류**와 건수·증감(§2.8 발표, 공개 정보).
 * 사건 원인(어느 조우·어느 縣)은 싣지 않는다(#343).
 */
data class HwihaRenownReasonDto(val kind: String, val label: String, val count: Int, val amount: Int)

/**
 * 본인에게만 보이는 대기 사건 — 아직 월단평이 적용하지 않은 집계. [stamp] 달의 사건이며, [amount] 는 지금
 * 곡선 기준 예상 증감이다. 원인([source]·[sourceLabel])은 본인 정보라 싣는다.
 */
data class HwihaRenownPendingEventDto(
    val kind: String,
    val label: String,
    val stamp: String,
    val source: String?,
    val sourceLabel: String?,
    val amount: Int,
)

data class HwihaYuedanRow(
    val rank: Int,
    val generalId: Int,
    val name: String,
    val nationId: Int,
    val nationName: String?,
    val nationColor: String?,
    val renown: Int,
    /** 마지막 월단평이 적용한 사유. 사건이 없던 장수는 빈 목록이다. */
    val reasons: List<HwihaRenownReasonDto> = emptyList(),
)

data class HwihaYuedanResponse(
    val status: String,
    val stamp: String? = null,
    val self: HwihaYuedanSelf? = null,
    val ranking: List<HwihaYuedanRow> = emptyList(),
    /** 본인의 다음 월단평 대기 사건(한 달에 종류당 한 건). */
    val selfPendingEvents: List<HwihaRenownPendingEventDto> = emptyList(),
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
 * [ledgerMonthly] 는 `hwiha-resource-production-v1` 원장의 설계 산출량이다. [monthly] 는 월 세입
 * (`HwihaMonthlyCountyIncome`)이 이번 달 이 縣 창고에 실제로 넣을 양이다 — 주인 없음·보급 끊김·창고 없음이면 0,
 * 창고 meta 가 깨져 엔진도 건너뛰면 `null` 이다. 평소에는 두 값이 같다.
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
    /** 상사 재원 판단에 쓰는 카드 인물의 현재 城. 인물이 없는 카드는 null. */
    val locationCityId: Int? = null,
)

data class HwihaRetinueResponse(
    val status: String,
    val renown: Int? = null,
    val costSum: Int? = null,
    val overCapacity: Boolean = false,
    val people: List<HwihaPersonCardDto> = emptyList(),
    val units: List<RetinueBugokDto> = emptyList(),
)

/** 「지난 순」 기록 한 줄. [kind] 는 `RecordKind`, [refs] 는 종류마다 다른 식별자 묶음이다. */
data class HwihaRecordEntryDto(val kind: String, val text: String, val refs: Map<String, Any?>)

/** 지난 순 하나(연·월·순). 기록이 없던 순도 빈 [entries] 로 싣는다 — 명령 목록 12순과 같은 칸 수다. */
data class HwihaLastTurnDto(
    val year: Int,
    val month: Int,
    val phase: Int,
    val phaseLabel: String,
    val entries: List<HwihaRecordEntryDto>,
)

/** 세력 요약 한 줄 — 본인 세력의 공개 사건(縣 점령·상실)과 세계 공개 사건(월단평 발표)만. */
data class HwihaNationSummaryEntryDto(
    val year: Int,
    val month: Int,
    val phase: Int,
    val phaseLabel: String,
    val kind: String,
    val text: String,
    val refs: Map<String, Any?>,
)

/** `/api/hwiha/last-turns` 응답. [turns] 는 최근 순부터다. */
data class HwihaLastTurnsResponse(
    val status: String,
    val turns: List<HwihaLastTurnDto> = emptyList(),
    val nationSummary: List<HwihaNationSummaryEntryDto> = emptyList(),
)
