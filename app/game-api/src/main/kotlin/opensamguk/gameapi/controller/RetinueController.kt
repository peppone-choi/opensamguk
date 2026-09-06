package opensamguk.gameapi.controller

import opensamguk.common.constants.UnitCatalog
import opensamguk.gameapi.dto.RetinueBugokDto
import opensamguk.gameapi.dto.RetinueResponse
import opensamguk.gameapi.dto.RetinueRetainerDto
import opensamguk.gameapi.dto.RetinueRulesDto
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.RetainerReadRepository
import opensamguk.logic.retainer.RetainerRules
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Phase 4X-A 읽기 API (spec v3 §6). 두 경로 모두 `GameApiSecurityConfig` 의 authenticated 목록에 있다.
 *  - `GET /api/my-retinue`: principal 없음 401 · 내 장수 없음 404(MyController `/api/my-page` idiom).
 *  - `GET /api/generals/{id}/retinue`: 401 익명 · 200 본인 · 200 같은 국가(둘 다 nationId ≠ 0) · 403 그 외 ·
 *    대상이 재야면 본인만(적국 장수의 사병·군량 누출 방지).
 * 전부 DB 원천(엔진 flush 결과), 쓰기 없음.
 */
@RestController
@RequestMapping("/api")
class RetinueController(
    private val resolver: GeneralResolver,
    private val generals: GeneralReadRepository,
    private val retinue: RetainerReadRepository,
) {
    @GetMapping("/my-retinue")
    fun myRetinue(@AuthenticationPrincipal userId: Long?): ResponseEntity<RetinueResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        return ResponseEntity.ok(build(me.general))
    }

    @GetMapping("/generals/{id}/retinue")
    fun generalRetinue(@AuthenticationPrincipal userId: Long?, @PathVariable id: Int): ResponseEntity<RetinueResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val target = generals.findById(id).orElse(null) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val allowed = target.id == me.general.id ||
            (me.nationId != 0 && target.nationId != 0 && me.nationId == target.nationId)
        if (!allowed) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        return ResponseEntity.ok(build(target))
    }

    private fun build(g: GeneralReadEntity): RetinueResponse {
        val retainers = retinue.retainersOf(g.id).map {
            RetinueRetainerDto(
                id = it.id, name = it.name, origin = it.origin,
                relation = it.relation, relationLabel = RetainerRules.RELATION_LABELS[it.relation] ?: it.relation,
                role = it.role, roleLabel = RetainerRules.ROLE_LABELS[it.role] ?: it.role,
                loyalty = it.loyalty, task = it.task, taskLabel = RetainerRules.TASK_LABELS[it.task] ?: it.task,
                hasOwnBugok = it.hasOwnBugok,
            )
        }
        val bugoks = retinue.bugoksOf(g.id).map {
            RetinueBugokDto(
                id = it.id, name = it.name, troops = it.troops, crewTypeId = it.crewTypeId,
                crewTypeName = crewTypeName(it.crewTypeId), training = it.training, morale = it.morale,
                fatigue = it.fatigue, provisions = it.provisions,
                provisionMonths = RetainerRules.provisionMonths(it.provisions, it.troops),
                commanderRetainerId = it.commanderRetainerId,
            )
        }
        return RetinueResponse(
            generalId = g.id, generalName = g.name, crew = g.crew, rice = g.rice, gold = g.gold,
            crewTypeId = g.crewTypeId, crewTypeName = crewTypeName(g.crewTypeId),
            retainers = retainers, bugoks = bugoks, rules = RULES,
        )
    }

    /** `UnitCatalog.byId` 는 id < 1000 에서 던진다(`general.crew_type_id DEFAULT 0`) — CityDetailController 선례 가드. */
    private fun crewTypeName(crewTypeId: Int): String =
        if (crewTypeId >= 1000) UnitCatalog.byId(crewTypeId)?.name ?: "-" else "-"

    companion object {
        private fun options(labels: Map<String, String>, order: Collection<String>) =
            order.map { mapOf("value" to it, "label" to (labels[it] ?: it)) }

        val RULES = RetinueRulesDto(
            maxRetainers = RetainerRules.MAX_RETAINERS,
            maxBugok = RetainerRules.MAX_BUGOK,
            pledgeCostGold = RetainerRules.PLEDGE_COST_GOLD,
            minBugokTroops = RetainerRules.MIN_BUGOK_TROOPS,
            retainerUpkeepGold = RetainerRules.RETAINER_UPKEEP_GOLD,
            retainerUpkeepRice = RetainerRules.RETAINER_UPKEEP_RICE,
            payGoldPer100Troops = RetainerRules.PAY_GOLD_PER_100_TROOPS,
            provisionPerTroopMonth = RetainerRules.PROVISION_PER_TROOP_MONTH,
            commanderMoraleBonus = RetainerRules.COMMANDER_MORALE_BONUS,
            relations = options(RetainerRules.RELATION_LABELS, listOf("staff", "lieutenant", "guest")),
            roles = options(RetainerRules.ROLE_LABELS, listOf("NONE", "STAFF", "GUARD", "QUARTERMASTER", "SCOUT", "ENVOY")),
            tasks = options(RetainerRules.TASK_LABELS, listOf("none", "domestic", "scout", "train")),
            provisional = RetainerRules.PROVISIONAL,
        )
    }
}
