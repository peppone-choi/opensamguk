package opensamguk.logic.war

import opensamguk.logic.world.BattlefieldLayout
import opensamguk.logic.world.ProvinceCellIndex

/**
 * 縣城 포위의 순수 규칙 — 포위 유지 판정, 순 경계 성 안 급식·사기, 항복 권고 판정.
 *
 * 승인값: 병력비 2배([MINIMUM_ATTACKER_RATIO], `armyEncirclement.minimumAttackerRatio`), 사기·항복
 * ([SiegeMorale], `siegeResolution`). 나머지(수비병 식량, 포위군 급식 판정, 항복 권고 문턱)는
 * [CampaignBalance] 의 확정값이다.
 *
 * 수비병 수는 縣治 城의 `defence`(수비) 값을 쓴다(`campaign-balance-v1.json` 확정값).
 */
object SiegeRules {
    /** 포위 유지에 필요한 최소 병력비(승인값). */
    const val MINIMUM_ATTACKER_RATIO = 2

    enum class Maintenance { MAINTAINED, INSUFFICIENT_RATIO, UNFED }

    enum class AssaultBlock(val message: String) {
        NOT_BESIEGING("포위 중인 縣이 없습니다."),
        TARGET_CHANGED("선택한 縣은 더 이상 이 군단의 포위 대상이 아닙니다."),
        BATTLE_PENDING("포위 군단이 조우 전투 중이라 공성 행동을 할 수 없습니다."),
        ASSAULT_NOT_READY("포위한 지 한 달(3순)이 지나야 강공할 수 있습니다."),
        STATE_UNAVAILABLE("포위 상태를 확인할 수 없습니다."),
    }

    /** The same selected county, corps order and turn gate are checked at options, intake and execution. */
    fun assaultReadiness(targetCountyId: Int, activeCountyId: Int?, turns: Int?, inBattle: Boolean,
        corpsMatches: Boolean, targetStillHostile: Boolean?): AssaultBlock? {
        if (activeCountyId == null) return AssaultBlock.NOT_BESIEGING
        if (activeCountyId != targetCountyId || targetStillHostile == false) return AssaultBlock.TARGET_CHANGED
        if (inBattle) return AssaultBlock.BATTLE_PENDING
        if (turns == null || !corpsMatches) return AssaultBlock.STATE_UNAVAILABLE
        if (turns < CampaignBalance.ASSAULT_MIN_SIEGE_TURNS) return AssaultBlock.ASSAULT_NOT_READY
        if (targetStillHostile == null) return AssaultBlock.STATE_UNAVAILABLE
        return null
    }

    /** Fail closed on stale terrain pins or an unusable combat zone; both readers and engine call this. */
    fun assaultLayout(cells: ProvinceCellIndex, provinceId: String, approachProvinceId: String): BattlefieldLayout? =
        try {
            val layout = (BattlefieldLayout.prepare(cells, provinceId, approachProvinceId)
                as? BattlefieldLayout.Result.Ready)?.layout
            layout?.takeIf { it.attackerZone.isNotEmpty() && it.defenderZone.isNotEmpty() }
        } catch (_: IllegalArgumentException) { null }
        catch (_: NoSuchElementException) { null }

    data class TurnSettlement(
        val rationDemand: Long,
        val rationServed: Long,
        val morale: Int,
        val surrendered: Boolean,
    )

    fun garrisonRationDemand(garrison: Int): Long {
        require(garrison >= 0) { "garrison must be nonnegative, was $garrison" }
        return Math.multiplyExact(garrison.toLong(), CampaignBalance.GARRISON_RATION_PER_SOLDIER_TURN)
    }

    /** 휴대 군량이 병력 × 최소 개월(월 소비 1) 이상인 부곡만 급식된 것으로 본다. */
    fun besiegerFed(troops: Int, provisions: Int): Boolean {
        require(troops >= 0 && provisions >= 0)
        return provisions.toLong() >= troops.toLong() * CampaignBalance.BESIEGER_MIN_PROVISION_MONTHS
    }

    /** 유지 조건 순서: 급식 → 병력비. 급식 실패는 원정 명령까지 끝내는 사유라 먼저 본다(armyEncirclement.interruption). */
    fun maintenance(besiegerTroops: Int, garrison: Int, fed: Boolean): Maintenance {
        require(besiegerTroops >= 0 && garrison >= 0)
        if (!fed) return Maintenance.UNFED
        if (besiegerTroops.toLong() < garrison.toLong() * MINIMUM_ATTACKER_RATIO) return Maintenance.INSUFFICIENT_RATIO
        return Maintenance.MAINTAINED
    }

    /**
     * 한 순의 성 안 급식과 사기. [grainAvailable] 은 縣 창고 곡(없으면 0 — 굶는다). 실제로 먹인 양만큼
     * 호출자가 창고에서 빼야 한다. 포위가 유지되는 순에만 부른다(`encircled = true`).
     */
    fun settleTurn(morale: Int, garrison: Int, grainAvailable: Long, alreadySurrendered: Boolean = false): TurnSettlement {
        require(grainAvailable >= 0)
        val demand = garrisonRationDemand(garrison)
        val served = minOf(demand, grainAvailable)
        val result = SiegeMorale.settle(morale, demand, served, encircled = true, alreadySurrendered = alreadySurrendered)
        return TurnSettlement(demand, served, result.morale, result.surrendered)
    }

    /** 항복 권고: 성 안 사기와 민심이 둘 다 문턱 이하일 때만 받아들인다. 결정론 — 난수 없음. */
    fun surrenderDemandAccepted(morale: Int, trust: Double): Boolean {
        require(morale in 0..SiegeMorale.MAX_MORALE)
        return morale <= CampaignBalance.SURRENDER_DEMAND_MAX_MORALE && trust <= CampaignBalance.SURRENDER_DEMAND_MAX_TRUST
    }
}
