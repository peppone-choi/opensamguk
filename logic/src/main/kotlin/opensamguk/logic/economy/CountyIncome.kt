package opensamguk.logic.economy

/**
 * HWIHA 縣 월간 생산. **게임 설계 수치이고 역사 수치가 아니다.**
 *
 * 규모 결정(2026-09-21, 사용자): 초안 규모를 그대로 쓴다 — 완전개발 기준 호당 월 전 20 · 곡 200.
 * 이 값은 기존 SAMMO 국가 세입(`IncomeTick`, 인구×개발비/30 을 연 1회)과 비교하면 전 약 1,371 배 ·
 * 곡 약 7,991 배다(1,168 縣 실측: 초안 월 전 50,053,689 · 곡 513,021,597 대 기존 월환산 전 36,513 ·
 * 곡 64,197). 두 배수가 다르므로 이 경제에서 곡은 기존보다 전에 비해 싸다. 그래서 이 규모는 **기존
 * 비용표를 물려받지 않는다** — HWIHA 비용은 처음부터 이 규모에 맞춰 적는다. 실제로 이 모듈이 들어올
 * 때 창고를 소모하는 호출자는 하나도 없었다(`HwihaResources` 사용처는 모델·정산 경계·씨앗 디코더뿐).
 *
 * 기존 국가/개인 재정과 함께 켜면 이중 재정이 된다. HWIHA 에서 `ProcessIncome`·`ProcessWarIncome` 은
 * 적용되지 않고 `ProcessSemiAnnual` 은 도시 성장만 남는다(`WorldActionContext`).
 */
object HwihaCountyIncome {
    /** 게임상 1 호 = 인구 5. 절삭한다. */
    const val POPULATION_PER_HOUSEHOLD = 5L

    /** 완전개발(개발치 = 상한) 기준 호당 월 생산. */
    const val MONEY_PER_HOUSEHOLD = 20L
    const val GRAIN_PER_HOUSEHOLD = 200L

    /**
     * 한 縣 의 생산 입력. 상한이 0 이면 그 축의 생산은 0 이다 — 0 을 나누지 않는다.
     *
     * [supplied] 는 기존 세입과 같은 규칙이다: 보급이 끊긴 縣 은 생산하지 않는다.
     * [ownerNationId] 가 0 이하면 무주 縣 이라 생산하지 않는다 — 오래 방치된 縣 을 점령해
     * 쌓인 재고를 한 번에 얻는 일을 만들지 않는다.
     */
    data class CountyState(
        val ownerNationId: Int,
        val population: Int,
        val commerce: Int,
        val commerceMax: Int,
        val agriculture: Int,
        val agricultureMax: Int,
        val supplied: Boolean,
    ) {
        init {
            require(population >= 0 && commerce >= 0 && commerceMax >= 0)
            require(agriculture >= 0 && agricultureMax >= 0)
        }
    }

    fun households(population: Int): Long {
        require(population >= 0)
        return population.toLong() / POPULATION_PER_HOUSEHOLD
    }

    /**
     * 월 생산. 철·목재·말은 이 식이 만들지 않는다 — 산지 근거가 있는 縣 에만 [sites] 로 더한다.
     *
     * 정확한 정수 연산이다. 곱한 뒤 나누므로 개발비를 먼저 실수로 만들어 정밀도를 잃지 않는다.
     */
    fun monthly(state: CountyState, sites: HwihaResources = HwihaResources()): HwihaResources {
        if (state.ownerNationId <= 0 || !state.supplied) return HwihaResources()
        val households = households(state.population)
        if (households == 0L) return sites
        return HwihaResources(
            money = scaled(households, MONEY_PER_HOUSEHOLD, state.commerce, state.commerceMax),
            grain = scaled(households, GRAIN_PER_HOUSEHOLD, state.agriculture, state.agricultureMax),
        ).credit(sites)
    }

    /** 호수 × 기준 × min(개발, 상한) / 상한, 절삭. 상한 0 은 0 이다. */
    private fun scaled(households: Long, perHousehold: Long, development: Int, maximum: Int): Long {
        if (maximum <= 0) return 0
        val effective = minOf(development.toLong(), maximum.toLong())
        return Math.multiplyExact(Math.multiplyExact(households, perHousehold), effective) / maximum
    }
}
