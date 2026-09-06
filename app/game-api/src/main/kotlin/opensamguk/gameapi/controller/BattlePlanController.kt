package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.BattlePlanDto
import opensamguk.gameapi.dto.BattlePlanRulesDto
import opensamguk.gameapi.dto.BattleReplayDetailResponse
import opensamguk.gameapi.dto.BattleReplayPhaseDto
import opensamguk.gameapi.dto.BattleReplayPlanDto
import opensamguk.gameapi.dto.BattleReplaySeedDto
import opensamguk.gameapi.dto.BattleReplaySettlementDto
import opensamguk.gameapi.dto.BattleReplaySummaryDto
import opensamguk.gameapi.dto.BattleStanceDto
import opensamguk.gameapi.dto.MyBattlePlansResponse
import opensamguk.gameapi.dto.OperationDateDto
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.BattlePlanReadRepository
import opensamguk.gameapi.read.BattleReplayReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.logic.war.plan.BattlePlanRules
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Phase 4X-C 읽기 API (spec v4.1 §6). 세 경로 모두 `GameApiSecurityConfig` authenticated, DB 원천(엔진 flush 결과), 쓰기 없음.
 *  - `GET /api/my-battle-plans`: 401 익명 · 장수 없음 404 · 200 `{generalId, plans(미소비만), rules}`
 *  - `GET /api/battles/replays?scope=nation|mine`: `nation` = 내 국가가 공격자 **또는** 수비자, 재야는 `mine` 만
 *  - `GET /api/battles/replays/{id}`: 401 · 404 · 403(공격국·수비국 어느 쪽도 아니고 본인도 아님) · 200
 */
@RestController
@RequestMapping("/api")
class BattlePlanController(
    private val resolver: GeneralResolver,
    private val cities: CityReadRepository,
    private val plans: BattlePlanReadRepository,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/my-battle-plans")
    fun myPlans(@AuthenticationPrincipal userId: Long?): ResponseEntity<MyBattlePlansResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val rows = plans.openPlansOf(me.general.id)
        return ResponseEntity.ok(
            MyBattlePlansResponse(
                generalId = me.general.id,
                plans = rows.map { p ->
                    BattlePlanDto(
                        id = p.id, targetCityId = p.targetCityId, targetCityName = cities.findById(p.targetCityId).orElse(null)?.name ?: "-",
                        stance = p.stance, stanceLabel = BattlePlanRules.STANCE_LABELS[p.stance] ?: p.stance,
                        retreatLossPct = p.retreatLossPct?.toInt(), retreatMoraleBelow = p.retreatMoraleBelow?.toInt(),
                        sealed = p.sealedAt != null, sealedAt = p.sealedAt?.toString(),
                        sealedDate = if (p.sealedYear != null) OperationDateDto(p.sealedYear!!.toInt(), p.sealedMonth?.toInt() ?: 1, p.sealedPhase?.toInt() ?: 1) else null,
                        resolved = false, version = p.version,
                    )
                },
                rules = RULES,
            ),
        )
    }

    @GetMapping("/battles/replays")
    fun replays(@AuthenticationPrincipal userId: Long?, @RequestParam(defaultValue = "nation") scope: String): ResponseEntity<List<BattleReplaySummaryDto>> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val rows = if (scope == "mine" || me.nationId == 0) plans.replaysOfGeneral(me.general.id) else plans.replaysOfNation(me.nationId)
        return ResponseEntity.ok(rows.map { summary(it) })
    }

    @GetMapping("/battles/replays/{id}")
    fun replay(@AuthenticationPrincipal userId: Long?, @PathVariable id: Int): ResponseEntity<BattleReplayDetailResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val me = resolver.resolve(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val r = plans.findReplay(id) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        val allowed = r.attackerGeneralId == me.general.id || (me.nationId != 0 && (r.attackerNationId == me.nationId || r.defenderNationId == me.nationId))
        if (!allowed) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        val parsed = runCatching { objectMapper.readTree(r.battlePhasesJson) }.getOrNull()
        val phases = parsed?.get("phases")?.map { n ->
            BattleReplayPhaseDto(
                i = n.path("i").asInt(), defId = n.path("defId").asInt(), def = n.path("def").asText(), defKind = n.path("defKind").asText(),
                contact = n.path("contact").asBoolean(), deadA = n.path("deadA").asInt(), deadD = n.path("deadD").asInt(), crewA = n.path("crewA").asInt(), hpD = n.path("hpD").asInt(),
            )
        }.orEmpty()
        val stopAtPhase = parsed?.path("stop")?.path("atPhase")?.takeIf { !it.isNull && !it.isMissingNode }?.asInt()
        return ResponseEntity.ok(
            BattleReplayDetailResponse(
                summary = summary(r),
                battlePhases = phases,
                settlement = BattleReplaySettlementDto(r.attackerCrewBefore, r.attackerCrewAfter, r.attackerDead, r.defenderDead, r.riceUsed, r.result == BattlePlanRules.RESULT_CONQUERED),
                plan = if (r.battlePlanId != null || r.planStance != null) BattleReplayPlanDto(
                    stance = r.planStance, stanceLabel = r.planStance?.let { BattlePlanRules.STANCE_LABELS[it] }, retreatLossPct = r.planRetreatLossPct?.toInt(),
                    retreatMoraleBelow = r.planRetreatMoraleBelow?.toInt(), planStop = r.planStop, planStopLabel = r.planStop?.let { BattlePlanRules.PLAN_STOP_LABELS[it] }, stopAtPhase = stopAtPhase,
                ) else null,
                seed = BattleReplaySeedDto(r.warSeed, r.inputHash, r.replayHash, r.schemaVersion.toInt()),
                operationId = r.operationId,
            ),
        )
    }

    private fun summary(r: BattleReplayReadEntity) = BattleReplaySummaryDto(
        id = r.id, year = r.year.toInt(), month = r.month.toInt(), phase = r.phase.toInt(),
        attackerGeneralId = r.attackerGeneralId, attackerName = r.attackerName, attackerNationId = r.attackerNationId,
        defenderCityId = r.defenderCityId, defenderCityName = r.defenderCityName, defenderNationId = r.defenderNationId,
        result = r.result, resultLabel = BattlePlanRules.RESULT_LABELS[r.result] ?: r.result,
        attackerDead = r.attackerDead, defenderDead = r.defenderDead,
        hasPlan = r.battlePlanId != null || r.planStance != null, planStop = r.planStop, planStopLabel = r.planStop?.let { BattlePlanRules.PLAN_STOP_LABELS[it] },
        operationId = r.operationId,
    )

    companion object {
        val RULES = BattlePlanRulesDto(
            stances = BattlePlanRules.STANCES.map { BattleStanceDto(it, BattlePlanRules.STANCE_LABELS[it] ?: it, BattlePlanRules.STANCE_DESCRIPTIONS[it] ?: "", true, null) } +
                BattlePlanRules.RESERVED_STANCES.map { BattleStanceDto(it, BattlePlanRules.RESERVED_STANCE_LABELS[it] ?: it, "", false, BattlePlanRules.REASON_STANCE_RESERVED) },
            retreatLossPctMin = BattlePlanRules.RETREAT_LOSS_PCT_MIN, retreatLossPctMax = BattlePlanRules.RETREAT_LOSS_PCT_MAX,
            retreatMoraleMin = BattlePlanRules.RETREAT_MORALE_MIN, retreatMoraleMax = BattlePlanRules.RETREAT_MORALE_MAX,
            provisional = BattlePlanRules.PROVISIONAL,
        )
    }
}
