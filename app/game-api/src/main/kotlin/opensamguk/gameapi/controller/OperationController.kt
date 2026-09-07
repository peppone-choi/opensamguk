package opensamguk.gameapi.controller

import opensamguk.common.constants.UnitCatalog
import opensamguk.gameapi.dto.OperationBoardPostDto
import opensamguk.gameapi.dto.OperationDateDto
import opensamguk.gameapi.dto.OperationDetailResponse
import opensamguk.gameapi.dto.OperationDto
import opensamguk.gameapi.dto.OperationKindDto
import opensamguk.gameapi.dto.OperationMilestonesDto
import opensamguk.gameapi.dto.OperationPersonDto
import opensamguk.gameapi.dto.OperationRulesDto
import opensamguk.gameapi.dto.OperationTargetDto
import opensamguk.gameapi.dto.OperationUnitDto
import opensamguk.gameapi.dto.OperationsResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.OperationReadEntity
import opensamguk.gameapi.read.OperationReadRepository
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.logic.operation.OperationRules
import opensamguk.logic.tick.GameDate
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Phase 4X-B 읽기 API (spec v4.1 §6). 두 경로 모두 `GameApiSecurityConfig` authenticated.
 *  - `GET /api/operations`: 401 익명 · 내 장수 없음 404 · 재야 → `{nationId 0, operations [], rules}` · 200
 *  - `GET /api/operations/{id}`: 401 · 404 · 타국 403 · 200 + 연결 회의실 글
 * 전부 DB 원천(엔진 flush 결과), 쓰기 없음. `remainingMonths` 는 진행 중에만(종료 null).
 */
@RestController
@RequestMapping("/api")
class OperationController(
    private val resolver: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val cities: CityReadRepository,
    private val operations: OperationReadRepository,
    private val retinue: RetainerReadRepository,
    private val boardPosts: BoardPostReadRepository,
    private val worldStates: WorldStateReadRepository,
    private val permissions: SecretPermissionReader,
) {
    @GetMapping("/operations")
    fun list(@AuthenticationPrincipal userId: Long?): ResponseEntity<OperationsResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val permission = permissions.of(me)
        if (me.nationId == 0) {
            return ResponseEntity.ok(OperationsResponse(nationId = 0, myPermission = permission, myGeneralId = me.general.id, operations = emptyList(), rules = RULES))
        }
        val rows = operations.operationsOf(me.nationId)
        val now = now()
        val units = operations.unitsOf(rows.map { it.id }).groupBy { it.operationId }
        val posts = boardPosts.findByOperationIds(rows.map { it.id }).groupBy { it.operationId }
        val dtos = rows.map { toDto(it, units[it.id].orEmpty(), posts[it.id].orEmpty().map { p -> p.id }, now) }
        return ResponseEntity.ok(OperationsResponse(nationId = me.nationId, myPermission = permission, myGeneralId = me.general.id, operations = dtos, rules = RULES))
    }

    @GetMapping("/operations/{id}")
    fun detail(@AuthenticationPrincipal userId: Long?, @PathVariable id: Int): ResponseEntity<OperationDetailResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val row = operations.findById(id) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        if (me.nationId == 0 || row.nationId != me.nationId) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val posts = boardPosts.findByOperationIds(listOf(row.id))
        val dto = toDto(row, operations.unitsOf(listOf(row.id)), posts.map { it.id }, now())
        return ResponseEntity.ok(
            OperationDetailResponse(
                operation = dto,
                boardPosts = posts.map { OperationBoardPostDto(id = it.id, title = it.title, authorName = it.authorName, createdAt = it.createdAt) },
            ),
        )
    }

    private fun now(): GameDate {
        val ws = worldStates.findAll().firstOrNull()
        return GameDate(ws?.currentYear ?: 0, ws?.currentMonth ?: 1, ws?.currentPhase ?: 1)
    }

    private fun toDto(o: OperationReadEntity, units: List<opensamguk.gameapi.read.OperationUnitReadEntity>, boardPostIds: List<Int>, now: GameDate): OperationDto {
        val target = cities.findById(o.targetCityId).orElse(null)
        val declaredBy = o.declaredByGeneralId?.let { generals.findById(it).orElse(null) }
        val deadline = GameDate(o.deadlineYear.toInt(), o.deadlineMonth.toInt(), o.deadlinePhase.toInt())
        val open = o.status in OperationRules.OPEN_STATUSES
        val milestones = OperationMilestonesDto(o.departed, o.arrived, o.supplied, o.objective)
        val count = listOf(o.departed, o.arrived, o.supplied, o.objective).count { it }
        return OperationDto(
            id = o.id, kind = o.kind, kindLabel = OperationRules.KIND_LABELS[o.kind] ?: o.kind, title = o.title, fallbackText = o.fallbackText,
            target = OperationTargetDto(o.targetCityId, target?.name ?: "-"),
            status = o.status, statusLabel = OperationRules.STATUS_LABELS[o.status] ?: o.status, closedReason = o.closedReason,
            declaredAt = OperationDateDto(o.declaredYear.toInt(), o.declaredMonth.toInt(), o.declaredPhase.toInt()),
            deadline = OperationDateDto(o.deadlineYear.toInt(), o.deadlineMonth.toInt(), o.deadlinePhase.toInt()),
            remainingMonths = if (open) OperationRules.remainingMonths(now, deadline).coerceAtLeast(1) else null,
            milestones = milestones, milestoneDisplayPct = count * OperationRules.MILESTONE_DISPLAY_PCT,
            units = units.map { u ->
                val g = generals.findById(u.generalId).orElse(null)
                val city = g?.let { cities.findById(it.cityId).orElse(null) }
                val bugok = u.bugokId?.let { bid -> g?.let { retinue.bugoksOf(it.id).firstOrNull { b -> b.id == bid } } }
                OperationUnitDto(
                    id = u.id, generalId = u.generalId, name = g?.name ?: "-", role = u.role, roleLabel = OperationRules.ROLE_LABELS[u.role] ?: u.role,
                    crew = g?.crew ?: 0, crewTypeName = crewTypeName(g?.crewTypeId ?: 0), bugokId = u.bugokId, bugokTroops = bugok?.troops,
                    cityId = g?.cityId ?: 0, cityName = city?.name ?: "-", picture = g?.picture, imageServer = g?.imageServer ?: 0,
                )
            },
            declaredBy = declaredBy?.let { OperationPersonDto(it.id, it.name) },
            boardPostIds = boardPostIds,
        )
    }

    private fun crewTypeName(crewTypeId: Int): String =
        if (crewTypeId >= 1000) UnitCatalog.byId(crewTypeId)?.name ?: "-" else "-"

    companion object {
        val RULES = OperationRulesDto(
            maxActivePerNation = OperationRules.MAX_ACTIVE_PER_NATION,
            minDeadlineMonths = OperationRules.MIN_DEADLINE_MONTHS,
            maxDeadlineMonths = OperationRules.MAX_DEADLINE_MONTHS,
            maxUnits = OperationRules.MAX_UNITS,
            failAtmosLoss = OperationRules.FAIL_ATMOS_LOSS,
            milestoneDisplayPct = OperationRules.MILESTONE_DISPLAY_PCT,
            kinds = OperationRules.KINDS.map { k ->
                val declarable = k in OperationRules.DECLARABLE_KINDS
                OperationKindDto(k, OperationRules.KIND_LABELS[k] ?: k, declarable, if (declarable) null else OperationRules.REASON_KIND_RESERVED)
            },
            roles = OperationRules.ROLES.map { mapOf("value" to it, "label" to (OperationRules.ROLE_LABELS[it] ?: it)) },
            provisional = OperationRules.PROVISIONAL,
        )
    }
}
