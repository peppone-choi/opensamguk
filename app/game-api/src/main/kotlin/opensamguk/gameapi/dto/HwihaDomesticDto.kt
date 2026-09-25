package opensamguk.gameapi.dto

import opensamguk.logic.input.Phase

/*
 * 휘하 내정 입력(배치·방침·공사) 조회 응답. status: READY | WRONG_RULE_PROFILE | UNAVAILABLE.
 * 가용 여부는 접수 가능성일 뿐이다 — 접수·효력 시점에 같은 규칙으로 다시 검사한다.
 */

data class HwihaCodeLabel(val code: String, val label: String)
data class HwihaReasonDto(val code: String, val reason: String)

/** 배치 목적지. 자리마다 한 칸만 차 있다. */
data class HwihaPlacementTargetDto(val countyId: Int? = null, val provinceId: String? = null, val nationId: Int? = null,
    val label: String? = null)

data class HwihaPlacementOrderDto(val requestId: String, val post: String, val postLabel: String, val target: HwihaPlacementTargetDto,
    val requestedAt: Phase)

/** state: MOVING(부임 행군 중) | ARRIVED(자리에 앉음). */
data class HwihaActivePlacementDto(val post: String, val postLabel: String, val target: HwihaPlacementTargetDto,
    val since: Phase, val arrivedAt: Phase?, val state: String)

data class HwihaPlacementCardDto(
    val cardId: Int, val generalId: Int?, val name: String, val relation: String, val provinceId: String?,
    val placeable: Boolean, val blocked: HwihaReasonDto?,
    val active: HwihaActivePlacementDto?, val pending: HwihaPlacementOrderDto?,
)

data class HwihaPostTargetDto(val countyId: Int? = null, val nationId: Int? = null, val name: String,
    val commanderyName: String? = null, val occupied: Boolean = false)

/** targets 가 null 이면 목록을 싣지 않는 자리다(정찰은 육상 省 id 아무 것, 군단장·해제는 목적지 없음). */
data class HwihaPostOptionDto(val post: String, val label: String, val available: Boolean, val blocked: HwihaReasonDto?,
    val targets: List<HwihaPostTargetDto>?)

data class HwihaPostsResponse(
    val status: String, val inputId: String = "placement.assign", val now: Phase? = null,
    val cards: List<HwihaPlacementCardDto> = emptyList(), val posts: List<HwihaPostOptionDto> = emptyList(),
)

data class HwihaPolicySettingDto(val policy: String, val label: String, val since: Phase)
/** policy null 은 거두기 대기다. */
data class HwihaPolicyOrderDto(val policy: String?, val label: String?, val requestedAt: Phase)
data class HwihaPolicyApplicationDto(val at: Phase, val policy: String, val label: String, val seat: String, val result: String)
data class HwihaSeatDto(val generalId: Int, val name: String, val placed: Boolean)
data class HwihaEffectivePolicyDto(val policy: String, val label: String, val source: String)

data class HwihaCountyPolicyDto(
    val countyId: Int, val name: String, val commanderyId: String?, val commanderyName: String?,
    val active: HwihaPolicySettingDto?, val pending: HwihaPolicyOrderDto?, val effective: HwihaEffectivePolicyDto?,
    val seat: HwihaSeatDto?, val lastApplied: HwihaPolicyApplicationDto?, val settable: Boolean, val blocked: HwihaReasonDto?,
)

data class HwihaCommanderyPolicyDto(
    val commanderyId: String, val name: String?, val countyIds: List<Int>,
    val active: HwihaPolicySettingDto?, val pending: HwihaPolicyOrderDto?, val settable: Boolean, val blocked: HwihaReasonDto?,
)

data class HwihaCorpsPolicyDto(val orderId: String, val commanderGeneralId: Int, val commanderName: String?,
    val active: HwihaPolicySettingDto?, val pending: HwihaPolicyOrderDto?, val settable: Boolean, val blocked: HwihaReasonDto?)

data class HwihaPoliciesResponse(
    val status: String, val inputId: String = "policy.set", val now: Phase? = null,
    val countyOptions: List<HwihaCodeLabel> = emptyList(), val corpsOptions: List<HwihaCodeLabel> = emptyList(),
    val defaultPolicy: HwihaCodeLabel? = null, val provisional: String? = null,
    val counties: List<HwihaCountyPolicyDto> = emptyList(), val commanderies: List<HwihaCommanderyPolicyDto> = emptyList(),
    val corps: List<HwihaCorpsPolicyDto> = emptyList(),
)

data class HwihaActiveWorkDto(
    val work: String, val label: String, val requestedAt: Phase, val progress: Int, val required: Int, val percent: Int,
    val remainingPhases: Int, val cost: HwihaStockDto, val charged: HwihaStockDto, val remainingCost: HwihaStockDto,
    val lastProgressAt: Phase?, val stopReason: String?, val stopReasonText: String?, val startsAtNextBoundary: Boolean,
)

data class HwihaCompletedWorkDto(val work: String, val label: String, val completedAt: Phase)

data class HwihaStartableWorkDto(val work: String, val label: String, val available: Boolean, val blocked: HwihaReasonDto?,
    val cost: HwihaStockDto, val requiredProgress: Int, val estimatedPhases: Int)

data class HwihaCountyWorksDto(
    val countyId: Int, val name: String, val commanderyName: String?, val warehouse: HwihaStockDto?,
    val active: HwihaActiveWorkDto?, val completed: List<HwihaCompletedWorkDto>, val startable: List<HwihaStartableWorkDto>,
)

data class HwihaWorksResponse(
    val status: String, val inputId: String = "work.start", val now: Phase? = null, val provisional: String? = null,
    val counties: List<HwihaCountyWorksDto> = emptyList(),
)
