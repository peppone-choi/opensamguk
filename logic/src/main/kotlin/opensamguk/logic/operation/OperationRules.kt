package opensamguk.logic.operation

import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.ServerClock

/**
 * Phase 4X-B 작전 — 순수 규칙 (specs/2026-09-06-operation-vertical-slice v4.1). Spring/DB/world 없음.
 *
 * 진척은 저장된 숫자를 올리는 게 아니라 달마다 세계 상태에서 **다시 계산**한 이정표 4개(불리언)다. 선언 가능 종류는
 * 생산자가 있는 3종([DECLARABLE_KINDS])뿐이고, 나머지 3종은 예약(강역·수역·장수 위치 생산자가 붙을 때).
 * 잠정 상수는 게이트 임계값이 아니라 입력 범위·상한이며 `rules.provisional = true` 로 노출된다.
 */
object OperationRules {
    const val PROVISIONAL = true

    const val MAX_ACTIVE_PER_NATION = 3
    const val MIN_DEADLINE_MONTHS = 1
    const val MAX_DEADLINE_MONTHS = 12
    const val MAX_UNITS = 12
    const val FAIL_ATMOS_LOSS = 5
    const val MILESTONE_DISPLAY_PCT = 25
    const val TITLE_MIN = 2
    const val TITLE_MAX = 40
    const val FALLBACK_MAX = 200

    const val KIND_CAPTURE_CITY = "capture_city"
    const val KIND_RELIEVE = "relieve"
    const val KIND_CUT_SUPPLY = "cut_supply"
    const val KIND_SECURE_ROUTE = "secure_route"
    const val KIND_PASS_THROUGH = "pass_through"
    const val KIND_BLOCKADE = "blockade"
    val KINDS: List<String> = listOf(KIND_CAPTURE_CITY, KIND_RELIEVE, KIND_CUT_SUPPLY, KIND_SECURE_ROUTE, KIND_PASS_THROUGH, KIND_BLOCKADE)
    /** 생산자 있는 종류(F1). 상수가 아니라 집합 — 예약 3종은 생산자 PR 이 더한다. */
    val DECLARABLE_KINDS: Set<String> = setOf(KIND_CAPTURE_CITY, KIND_RELIEVE, KIND_CUT_SUPPLY)
    val KIND_LABELS: Map<String, String> = mapOf(
        KIND_CAPTURE_CITY to "도시 점령", KIND_RELIEVE to "구원", KIND_CUT_SUPPLY to "보급로 차단",
        KIND_SECURE_ROUTE to "도로 확보", KIND_PASS_THROUGH to "통과", KIND_BLOCKADE to "봉쇄",
    )
    const val REASON_KIND_RESERVED = "아직 선언할 수 없는 작전 종류입니다."

    val ROLES: List<String> = listOf("main", "flank", "scout", "convoy", "reserve")
    /** S7 — 아트보드 08 의 본대·별동·호송이 1차 표기, Jira 3-e 명칭은 괄호. */
    val ROLE_LABELS: Map<String, String> = mapOf("main" to "본대", "flank" to "별동", "scout" to "정찰", "convoy" to "호송", "reserve" to "예비")

    const val STATUS_DECLARED = "declared"
    const val STATUS_ACTIVE = "active"
    const val STATUS_ACHIEVED = "achieved"
    const val STATUS_FAILED = "failed"
    const val STATUS_CLOSED = "closed"
    val OPEN_STATUSES: Set<String> = setOf(STATUS_DECLARED, STATUS_ACTIVE)
    val STATUS_LABELS: Map<String, String> = mapOf(
        STATUS_DECLARED to "선언", STATUS_ACTIVE to "진행 중", STATUS_ACHIEVED to "달성", STATUS_FAILED to "실패", STATUS_CLOSED to "종료",
    )
    const val CLOSED_ACHIEVED = "achieved"
    const val CLOSED_DEADLINE = "deadline"
    const val CLOSED_COMMAND = "command"
    const val CLOSED_NATION_GONE = "nation_gone"
    const val CLOSED_TARGET_GONE = "target_gone"

    const val REASON_INPUT = "올바르지 않은 입력입니다."
    const val REASON_NO_NATION = "국가에 소속되어있지 않습니다."
    const val REASON_NO_PERMISSION = "권한이 부족합니다. 수뇌부가 아닙니다."
    const val REASON_NO_TARGET = "목표를 찾을 수 없습니다."
    const val REASON_ALREADY_OURS = "이미 아군 도시입니다."
    const val REASON_NOT_ENEMY = "적국 도시가 아닙니다."
    const val REASON_NOT_OURS = "아군 도시가 아닙니다."
    const val REASON_NATION_FULL = "진행 중인 작전이 가득 찼습니다."
    const val REASON_NO_OPERATION = "작전이 없습니다."
    const val REASON_ENDED = "종료된 작전입니다."
    const val REASON_ALREADY_JOINED = "이미 참여 중입니다."
    const val REASON_OTHER_OPERATION = "이미 다른 작전에 참여 중입니다."
    const val REASON_UNITS_FULL = "작전 편성이 가득 찼습니다."
    const val REASON_NO_BUGOK = "부곡이 없습니다."
    const val REASON_NOT_JOINED = "참여하지 않은 작전입니다."

    // ── 날짜 산술 (P1·R6: 공개 ServerClock.advance 만 쓴다) ──
    fun absoluteTurn(d: GameDate): Int = d.year * 36 + (d.month - 1) * 3 + (d.phase - 1)
    fun firstPhaseOnOrAfter(d: GameDate): GameDate = if (d.phase == 1) d else ServerClock.advance(d, 4 - d.phase)
    fun addMonths(d: GameDate, months: Int): GameDate = ServerClock.advance(d, 3 * months)
    fun deadlineFor(declaredAt: GameDate, months: Int): GameDate = addMonths(firstPhaseOnOrAfter(declaredAt), months)
    /** 진행 중에만(≥ 1). 종료 상태는 호출자가 null 로 둔다(P3). */
    fun remainingMonths(now: GameDate, deadline: GameDate): Int = (absoluteTurn(deadline) - absoluteTurn(now) + 2) / 3
    fun deadlineReached(now: GameDate, deadline: GameDate): Boolean = absoluteTurn(now) >= absoluteTurn(deadline)

    // ── ③ 입력 ──
    sealed interface DeclareInput {
        data class Ok(val kind: String, val targetCityId: Int, val title: String, val fallbackText: String?, val deadlineMonths: Int) : DeclareInput
        data class Denied(val reason: String) : DeclareInput
    }

    fun declareInput(kind: String?, targetCityId: Int?, title: String?, fallbackText: String?, deadlineMonths: Int?): DeclareInput {
        if (kind == null || kind !in KINDS) return DeclareInput.Denied(REASON_INPUT)
        if (targetCityId == null) return DeclareInput.Denied(REASON_INPUT)
        val t = title?.trim() ?: return DeclareInput.Denied(REASON_INPUT)
        val len = t.codePointCount(0, t.length)
        if (len < TITLE_MIN || len > TITLE_MAX) return DeclareInput.Denied(REASON_INPUT)
        val fb = fallbackText?.trim()?.takeIf { it.isNotEmpty() }
        if (fb != null && fb.codePointCount(0, fb.length) > FALLBACK_MAX) return DeclareInput.Denied(REASON_INPUT)
        if (deadlineMonths == null || deadlineMonths < MIN_DEADLINE_MONTHS || deadlineMonths > MAX_DEADLINE_MONTHS) return DeclareInput.Denied(REASON_INPUT)
        return DeclareInput.Ok(kind, targetCityId, t, fb, deadlineMonths)
    }

    // ── ④ 상태 게이트 순서(§4) ──
    /** 선언: 재야 → 권한 → 예약 종류 → 목표 없음 → 종류별 소유 조건 → 상한. */
    fun declareDeny(permission: Int, kind: String, targetNationId: Int?, myNationId: Int, openCount: Int): String? = when {
        permission < 0 || myNationId == 0 -> REASON_NO_NATION
        permission < 2 -> REASON_NO_PERMISSION
        kind !in DECLARABLE_KINDS -> REASON_KIND_RESERVED
        targetNationId == null -> REASON_NO_TARGET
        kind == KIND_CAPTURE_CITY && targetNationId == myNationId -> REASON_ALREADY_OURS
        kind == KIND_CUT_SUPPLY && (targetNationId == 0 || targetNationId == myNationId) -> REASON_NOT_ENEMY
        kind == KIND_RELIEVE && targetNationId != myNationId -> REASON_NOT_OURS
        openCount >= MAX_ACTIVE_PER_NATION -> REASON_NATION_FULL
        else -> null
    }

    fun joinInputDeny(operationId: Int?, role: String?): String? = when {
        operationId == null || role == null || role !in ROLES -> REASON_INPUT
        else -> null
    }

    /** 참여: 작전 없음·타국 → 종료 → 이미 참여 → 다른 작전 참여 중(같은 국가) → 상한 → 부곡. */
    fun joinDeny(
        operationExistsForMyNation: Boolean, status: String?, alreadyJoined: Boolean, joinedOtherOpen: Boolean,
        unitCount: Int, bugokId: Int?, bugokOwned: Boolean,
    ): String? = when {
        !operationExistsForMyNation -> REASON_NO_OPERATION
        status !in OPEN_STATUSES -> REASON_ENDED
        alreadyJoined -> REASON_ALREADY_JOINED
        joinedOtherOpen -> REASON_OTHER_OPERATION
        unitCount >= MAX_UNITS -> REASON_UNITS_FULL
        bugokId != null && !bugokOwned -> REASON_NO_BUGOK
        else -> null
    }

    /** 이탈: 작전 없음(국가 검사 없음, N5) → 미참여. */
    fun leaveDeny(operationExists: Boolean, joined: Boolean): String? = when {
        !operationExists -> REASON_NO_OPERATION
        !joined -> REASON_NOT_JOINED
        else -> null
    }

    /** 종료: 작전 없음·타국 → 권한 → 이미 종료. */
    fun closeDeny(operationExistsForMyNation: Boolean, permission: Int, status: String?): String? = when {
        !operationExistsForMyNation -> REASON_NO_OPERATION
        permission < 2 -> REASON_NO_PERMISSION
        status !in OPEN_STATUSES -> REASON_ENDED
        else -> null
    }

    // ── 이정표 재계산 (§5 표) ──
    data class UnitView(val cityId: Int, val joinedCityId: Int)
    data class CityView(val nationId: Int, val supplied: Boolean)
    data class MilestoneInput(
        val kind: String,
        val nationId: Int,
        val targetCityId: Int,
        val target: CityView,
        val units: List<UnitView>,
        /** unit 이 서 있는 도시 → 도시 상태(엔진이 채운다). */
        val cityOf: Map<Int, CityView>,
        /** 목표에 인접한 도시 id(엔진이 `CalcCityDistance.nearCity(target, 1, variant)` 로 구한다). */
        val adjacentCityIds: Set<Int>,
        val atDeadline: Boolean,
    )
    data class Milestones(val departed: Boolean, val arrived: Boolean, val supplied: Boolean, val objective: Boolean) {
        val count: Int get() = listOf(departed, arrived, supplied, objective).count { it }
        fun or(prev: Milestones) = Milestones(departed || prev.departed, arrived || prev.arrived, supplied || prev.supplied, objective || prev.objective)
    }

    fun milestones(i: MilestoneInput): Milestones {
        val departed = i.units.any { it.cityId != it.joinedCityId || it.cityId == i.targetCityId }
        val arrived = i.units.any { it.cityId == i.targetCityId }
        val supplied = when (i.kind) {
            KIND_RELIEVE -> i.target.supplied
            else -> i.units.any { u -> u.cityId in i.adjacentCityIds && (i.cityOf[u.cityId]?.let { it.nationId == i.nationId && it.supplied } ?: false) }
        }
        val objective = when (i.kind) {
            KIND_CAPTURE_CITY -> i.target.nationId == i.nationId
            KIND_RELIEVE -> i.atDeadline && i.target.nationId == i.nationId
            KIND_CUT_SUPPLY -> i.target.nationId != 0 && i.target.nationId != i.nationId && !i.target.supplied
            else -> false
        }
        return Milestones(departed, arrived, supplied, objective)
    }

    /** 목표 소멸(N2): cut_supply 의 목표가 공백지·아군이 됐다. */
    fun targetGone(kind: String, nationId: Int, target: CityView): Boolean =
        kind == KIND_CUT_SUPPLY && (target.nationId == 0 || target.nationId == nationId)

    sealed interface Transition {
        data object None : Transition
        data object Achieved : Transition
        data object Failed : Transition
        data object TargetGone : Transition
    }

    /** ③ 목표 소멸 → ④ 전이(달성 → 기한). 호출자는 이정표 재계산 뒤에 부른다(P5 순서). */
    fun transition(kind: String, nationId: Int, target: CityView, milestones: Milestones, now: GameDate, deadline: GameDate): Transition = when {
        targetGone(kind, nationId, target) -> Transition.TargetGone
        milestones.objective -> Transition.Achieved
        deadlineReached(now, deadline) -> Transition.Failed
        else -> Transition.None
    }

    fun displayPct(m: Milestones): Int = m.count * MILESTONE_DISPLAY_PCT
}
