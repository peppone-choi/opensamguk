package opensamguk.logic.war.hwiha

/**
 * 縣城 포위의 순수 규칙 — 포위 유지 판정, 순 경계 성 안 급식·사기, 항복 권고 판정.
 *
 * 승인값: 병력비 2배([MINIMUM_ATTACKER_RATIO], `armyEncirclement.minimumAttackerRatio`), 사기·항복
 * ([HwihaSiegeMorale], `siegeResolution`). 나머지(수비병 식량, 포위군 급식 판정, 항복 권고 문턱)는
 * [HwihaS3Provisional] 의 임시값이다.
 *
 * 수비병 수는 縣治 城의 `defence`(수비) 값을 쓴다 — 이것도 임시 대응이다(`hwiha-s3-provisional-v1.json`).
 */
object HwihaSiegeRules {
    /** 포위 유지에 필요한 최소 병력비(승인값). */
    const val MINIMUM_ATTACKER_RATIO = 2

    enum class Maintenance { MAINTAINED, INSUFFICIENT_RATIO, UNFED }

    data class TurnSettlement(
        val rationDemand: Long,
        val rationServed: Long,
        val morale: Int,
        val surrendered: Boolean,
    )

    fun garrisonRationDemand(garrison: Int): Long {
        require(garrison >= 0) { "garrison must be nonnegative, was $garrison" }
        return Math.multiplyExact(garrison.toLong(), HwihaS3Provisional.GARRISON_RATION_PER_SOLDIER_TURN)
    }

    /** 휴대 군량이 병력 × 최소 개월(월 소비 1) 이상인 부곡만 급식된 것으로 본다. */
    fun besiegerFed(troops: Int, provisions: Int): Boolean {
        require(troops >= 0 && provisions >= 0)
        return provisions.toLong() >= troops.toLong() * HwihaS3Provisional.BESIEGER_MIN_PROVISION_MONTHS
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
        val result = HwihaSiegeMorale.settle(morale, demand, served, encircled = true, alreadySurrendered = alreadySurrendered)
        return TurnSettlement(demand, served, result.morale, result.surrendered)
    }

    /** 항복 권고: 성 안 사기와 민심이 둘 다 문턱 이하일 때만 받아들인다. 결정론 — 난수 없음. */
    fun surrenderDemandAccepted(morale: Int, trust: Double): Boolean {
        require(morale in 0..HwihaSiegeMorale.MAX_MORALE)
        return morale <= HwihaS3Provisional.SURRENDER_DEMAND_MAX_MORALE && trust <= HwihaS3Provisional.SURRENDER_DEMAND_MAX_TRUST
    }
}
