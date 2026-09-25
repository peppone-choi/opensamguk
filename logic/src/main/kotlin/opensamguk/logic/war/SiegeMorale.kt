package opensamguk.logic.war

/**
 * 포위된 縣城 수비 사기와 항복 판정.
 *
 * 정본은 `data/curated/han/march-tempo-targets-v1.json` 의 `siegeResolution` 이고(2026-09-20 사용자
 * 승인, 위임 결정), 이 객체는 그 규칙을 그대로 옮긴 것이다. 사기는 정수 10000 = 100% 로 센다 —
 * 부분 급식의 내림이 사기를 공짜로 살려 주지 않게 하려고 실수를 쓰지 않는다.
 *
 * 범위 밖: 병력 0, 민간 소비, 강공·전투 손실, 공격군의 포위 유지 판정, 항복군 처우.
 * 점령 정산은 [CountyCapture] 가 맡는다.
 */
object SiegeMorale {
    /** 정수 사기 상한 — 10000 = 100%. */
    const val MAX_MORALE = 10000

    /** 포위 시작 사기. */
    const val INITIAL_MORALE = 10000

    /** 군량을 한 순 전부 굶었을 때 잃는 사기(25%). */
    const val FULL_SHORTFALL_MORALE_LOSS = 2500

    /** 군량을 전부 먹은 순에만 회복하는 사기(12.5%). */
    const val FULLY_FED_MORALE_RECOVERY = 1250

    /**
     * 한 순의 급식·사기 정산 결과.
     *
     * @property morale 정산 뒤 사기.
     * @property surrendered 이 순에 항복이 **발생했는지**. 이미 항복한 성에서는 항상 false 다.
     */
    data class Settlement(val morale: Int, val surrendered: Boolean)

    /**
     * 한 순을 정산한다.
     *
     * 부족분에 비례해 사기를 깎고(올림), 전부 먹은 순에만 회복시킨다. 정산 **뒤** 사기가 0 이고 그 순
     * 포위가 유지되고 있으면 최초 1회 항복한다. 같은 순에 포위가 풀리면(`encircled = false`) 사기가
     * 0 이어도 항복하지 않는다 — 구원이 제때 닿은 것이다. 이미 항복한 성은 다시 항복하지 않으므로,
     * 항복 뒤에 도착한 보급이 결과를 취소하지도 않는다.
     *
     * @param morale 정산 전 사기(0..[MAX_MORALE]).
     * @param rationDemand 이 순에 필요한 군량. 0 이면 굶길 것이 없어 완전 급식으로 본다.
     * @param rationServed 이 순에 **실제로 먹인** 군량. 수요를 넘겨도 완전 급식으로만 센다.
     * @param encircled 이 순 급식 정산 시점에 포위가 유지되고 있는지.
     * @param alreadySurrendered 이 성이 이미 항복했는지.
     */
    fun settle(
        morale: Int,
        rationDemand: Long,
        rationServed: Long,
        encircled: Boolean,
        alreadySurrendered: Boolean = false,
    ): Settlement {
        require(morale in 0..MAX_MORALE) { "morale must be within 0..$MAX_MORALE, was $morale" }
        require(rationDemand >= 0L) { "rationDemand must be nonnegative, was $rationDemand" }
        require(rationServed >= 0L) { "rationServed must be nonnegative, was $rationServed" }

        val unmet = (rationDemand - rationServed).coerceAtLeast(0L)
        val next = if (unmet == 0L) {
            (morale + FULLY_FED_MORALE_RECOVERY).coerceAtMost(MAX_MORALE)
        } else {
            // 올림: 사기 1 에서도 극소 부족이 1 을 깎아 항복까지 갈 수 있다(정본 scope).
            val loss = (FULL_SHORTFALL_MORALE_LOSS.toLong() * unmet + rationDemand - 1L) / rationDemand
            (morale - loss).coerceAtLeast(0L).toInt()
        }
        return Settlement(next, surrendered = !alreadySurrendered && encircled && next == 0)
    }
}
