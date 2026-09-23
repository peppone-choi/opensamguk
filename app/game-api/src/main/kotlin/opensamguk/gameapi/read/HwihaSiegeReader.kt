package opensamguk.gameapi.read

import opensamguk.gameapi.dto.*
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.input.HwihaDeploymentState
import opensamguk.logic.war.hwiha.HwihaSiegeRules
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 공성 조회(읽기 전용). 인증은 휘하 화면 조회([HwihaCampReader])와 같다: `?generalId=` 장수의 `userId` 가
 * principal 과 같아야 하고 아니면 [HwihaCampForbidden](403). 보이는 포위는 **관여한 것**이다 — 조회 장수가
 * 포위 지휘관이거나, 그 장수의 세력이 포위·수비 세력인 행. 시야 규칙이 생기기 전에는 남의 포위를 보여 주지 않는다.
 * 판정 값(항복 권고 문턱·급식)은 엔진과 같은 [HwihaSiegeRules] 를 부른다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class HwihaSiegeReader(
    private val generals: GeneralReadRepository,
    private val worlds: WorldStateReadRepository,
    private val nations: NationReadRepository,
    private val cities: CityReadRepository,
    private val retainers: RetainerReadRepository,
    private val sieges: HwihaSiegeReadRepository,
) {
    fun sieges(generalId: Int, userId: Long): HwihaSiegesResponse {
        val actor = generals.findById(generalId).orElse(null) ?: throw HwihaCampForbidden()
        if (userId <= 0 || userId > Int.MAX_VALUE || actor.userId?.toLongOrNull() != userId) throw HwihaCampForbidden()
        val world = worlds.findProcessWorld() ?: return HwihaSiegesResponse("UNAVAILABLE")
        if (actor.worldId != world.id) return HwihaSiegesResponse("UNAVAILABLE")
        if (world.config["ruleProfile"] != "HWIHA") return HwihaSiegesResponse("WRONG_RULE_PROFILE")
        val nationNames = nations.findAll().associate { it.id to it.name }
        val rows = sieges.involving(actor.id, actor.nationId).map { row ->
            val city = cities.findById(row.countyId).orElse(null)
            val besieger = generals.findById(row.besiegerGeneralId).orElse(null)
            val grain = city?.let { runCatching { HwihaCountyWarehouse.read(it.meta, it.id)?.stock?.grain }.getOrNull() }
            val (troops, fed) = corpsOf(row)
            val active = row.status == "ACTIVE"
            val trust = city?.trust ?: 0.0
            HwihaSiegeDto(
                countyId = row.countyId, countyName = city?.name, status = row.status, endReason = row.endReason,
                besieger = HwihaSiegePartyDto(row.besiegerGeneralId, besieger?.name, row.besiegerNationId, nationNames[row.besiegerNationId]),
                defenderNationId = row.defenderNationId, defenderNationName = nationNames[row.defenderNationId],
                startedAt = HwihaSiegePhaseDto(row.startedYear, row.startedMonth, row.startedPhase), turns = row.turns,
                grain = grain, morale = row.morale, garrison = row.garrison, trust = trust,
                countySupplied = (city?.supplyState ?: 0) != 0, besiegerTroops = troops, besiegerFed = fed,
                canAct = active && row.besiegerGeneralId == actor.id,
                surrenderDemandAccepted = active && HwihaSiegeRules.surrenderDemandAccepted(row.morale, trust),
                timeline = row.timeline,
            )
        }
        return HwihaSiegesResponse("READY", rows)
    }

    /** 포위 군단(주인의 출전 기록 → 부곡)의 병력과 급식 판정. 읽을 수 없으면 (null, null). */
    private fun corpsOf(row: HwihaSiegeReadRow): Pair<Int?, Boolean?> {
        val owner = generals.findById(row.besiegerOwnerGeneralId).orElse(null) ?: return null to null
        val corps = runCatching { HwihaDeploymentState.read(owner.meta) }.getOrNull()?.corps
            ?.singleOrNull { it.orderId == row.besiegerOrderId } ?: return null to null
        val units = retainers.bugoksOf(owner.id).filter { it.id in corps.bugokIds }
        if (units.size != corps.bugokIds.size) return null to null
        return units.sumOf { it.troops } to units.all { HwihaSiegeRules.besiegerFed(it.troops, it.provisions) }
    }
}
