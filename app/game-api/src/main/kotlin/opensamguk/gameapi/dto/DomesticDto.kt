package opensamguk.gameapi.dto

import opensamguk.logic.input.Phase

/*
 * 휘하 내정 입력(배치·방침·공사) 조회 응답. status: READY | WRONG_RULE_PROFILE | UNAVAILABLE.
 * 가용 여부는 접수 가능성일 뿐이다 — 접수·효력 시점에 같은 규칙으로 다시 검사한다.
 */

data class CodeLabel(val code: String, val label: String)
data class ReasonDto(val code: String, val reason: String)

/** 배치 목적지. 자리마다 한 칸만 차 있다. */
data class PlacementTargetDto(val countyId: Int? = null, val provinceId: String? = null, val nationId: Int? = null,
    val label: String? = null)

data class PlacementOrderDto(val requestId: String, val post: String, val postLabel: String, val target: PlacementTargetDto,
    val requestedAt: Phase)

/** state: MOVING(부임 행군 중) | ARRIVED(자리에 앉음). */
data class ActivePlacementDto(val post: String, val postLabel: String, val target: PlacementTargetDto,
    val since: Phase, val arrivedAt: Phase?, val state: String)

data class PlacementCardDto(
    val cardId: Int, val generalId: Int?, val name: String, val relation: String, val provinceId: String?,
    val placeable: Boolean, val blocked: ReasonDto?,
    val active: ActivePlacementDto?, val pending: PlacementOrderDto?,
)

data class PostTargetDto(val countyId: Int? = null, val nationId: Int? = null, val name: String,
    val commanderyName: String? = null, val occupied: Boolean = false)

/** targets 가 null 이면 목록을 싣지 않는 자리다(정찰은 육상 省 id 아무 것, 군단장·해제는 목적지 없음). */
data class PostOptionDto(val post: String, val label: String, val available: Boolean, val blocked: ReasonDto?,
    val targets: List<PostTargetDto>?)

data class PostsResponse(
    val status: String, val inputId: String = "placement.assign", val now: Phase? = null,
    val cards: List<PlacementCardDto> = emptyList(), val posts: List<PostOptionDto> = emptyList(),
)

data class PolicySettingDto(val policy: String, val label: String, val since: Phase)
/** policy null 은 거두기 대기다. */
data class PolicyOrderDto(val policy: String?, val label: String?, val requestedAt: Phase)
data class PolicyApplicationDto(val at: Phase, val policy: String, val label: String, val seat: String, val result: String)
data class SeatDto(val generalId: Int, val name: String, val placed: Boolean)
data class EffectivePolicyDto(val policy: String, val label: String, val source: String)

data class CountyPolicyDto(
    val countyId: Int, val name: String, val commanderyId: String?, val commanderyName: String?,
    val active: PolicySettingDto?, val pending: PolicyOrderDto?, val effective: EffectivePolicyDto?,
    val seat: SeatDto?, val lastApplied: PolicyApplicationDto?, val settable: Boolean, val blocked: ReasonDto?,
)

data class CommanderyPolicyDto(
    val commanderyId: String, val name: String?, val countyIds: List<Int>,
    val active: PolicySettingDto?, val pending: PolicyOrderDto?, val settable: Boolean, val blocked: ReasonDto?,
)

data class CorpsPolicyDto(val orderId: String, val commanderGeneralId: Int, val commanderName: String?,
    val active: PolicySettingDto?, val pending: PolicyOrderDto?, val settable: Boolean, val blocked: ReasonDto?)

data class PoliciesResponse(
    val status: String, val inputId: String = "policy.set", val now: Phase? = null,
    val countyOptions: List<CodeLabel> = emptyList(), val corpsOptions: List<CodeLabel> = emptyList(),
    val defaultPolicy: CodeLabel? = null, val provisional: String? = null,
    val counties: List<CountyPolicyDto> = emptyList(), val commanderies: List<CommanderyPolicyDto> = emptyList(),
    val corps: List<CorpsPolicyDto> = emptyList(),
)

data class ActiveWorkDto(
    val work: String, val label: String, val requestedAt: Phase, val progress: Int, val required: Int, val percent: Int,
    val remainingPhases: Int, val cost: StockDto, val charged: StockDto, val remainingCost: StockDto,
    val lastProgressAt: Phase?, val stopReason: String?, val stopReasonText: String?, val startsAtNextBoundary: Boolean,
)

data class CompletedWorkDto(val work: String, val label: String, val completedAt: Phase)

data class StartableWorkDto(val work: String, val label: String, val available: Boolean, val blocked: ReasonDto?,
    val cost: StockDto, val requiredProgress: Int, val estimatedPhases: Int)

data class CountyWorksDto(
    val countyId: Int, val name: String, val commanderyName: String?, val warehouse: StockDto?,
    val active: ActiveWorkDto?, val completed: List<CompletedWorkDto>, val startable: List<StartableWorkDto>,
)

data class WorksResponse(
    val status: String, val inputId: String = "work.start", val now: Phase? = null, val provisional: String? = null,
    val counties: List<CountyWorksDto> = emptyList(),
)
