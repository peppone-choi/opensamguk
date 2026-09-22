package opensamguk.logic.war.hwiha

/**
 * 縣 점령 정산.
 *
 * 정본은 정본 설계 §10 의 점령 항목과 `data/curated/han/march-tempo-targets-v1.json` 의
 * `siegeResolution.captureSettlement`(사용자 위임 결정)다. 규칙은 넷이다.
 *
 * - **縣治 함락 시 縣 전체가 이전한다**(R1). 중립 縣도 점령할 수 있다.
 * - **창고는 그 縣 에 실물로 남고 통제자만 바뀐다**(`KEEP_IN_COUNTY_UNDER_NEW_OWNER`).
 *   잔고를 승자 국고에 가산하지 않는다 — 그러면 같은 물자를 두 번 세는 것이다.
 * - **수비대는 무장 해제하고 동수가 그 縣 의 현지 인구로 돌아간다**(`DISARM_TO_COUNTY_CIVILIANS`).
 *   승자 군대에 자동 편입하지 않는다.
 * - **같은 순 월세입은 점령 뒤 소유자에게 귀속한다**(`OWNER_AFTER_CAPTURE`).
 *
 * 기준은 평화 항복이다 — 인구·재고 손실이 없다. 강공 손실·항복군 처우·수송 반송은 범위 밖이다.
 * 항복 판정은 [HwihaSiegeMorale] 가 맡는다.
 */
object HwihaCountyCapture {
    /**
     * 점령 직전 縣 상태.
     *
     * @property garrisonTroops 무장 해제 대상 수비병. 승자에게 편입되지 않고 현지 인구가 된다.
     */
    data class CountyBefore(
        val countyId: Int,
        val ownerNationId: Int,
        val population: Int,
        val garrisonTroops: Int,
    )

    /**
     * 점령 정산 결과.
     *
     * @property disarmedToCivilians 인구로 돌아간 수비병 수 — 호출자가 기록·검증에 쓴다.
     */
    data class Settlement(
        val countyId: Int,
        val ownerNationId: Int,
        val population: Int,
        val garrisonTroops: Int,
        val disarmedToCivilians: Int,
    )

    /**
     * [before] 를 [captorNationId] 가 점령한 결과를 계산한다.
     *
     * 창고는 결과에 담지 않는다 — 손대지 않는 것이 규칙이므로, 호출자가 창고를 그대로 두고 통제자만
     * 縣 소유와 함께 넘기면 된다. 창고를 국고로 옮기는 호출자는 규칙을 어기는 것이다.
     *
     * @throws IllegalArgumentException 점령자가 이미 소유자일 때. 점령이 아니라 무동작이므로 조용히
     *   통과시키면 호출부의 버그를 삼킨다.
     */
    fun settle(before: CountyBefore, captorNationId: Int): Settlement {
        require(before.population >= 0) { "population must be nonnegative, was ${before.population}" }
        require(before.garrisonTroops >= 0) { "garrisonTroops must be nonnegative, was ${before.garrisonTroops}" }
        require(captorNationId != before.ownerNationId) {
            "captor ${captorNationId} already owns county ${before.countyId}"
        }
        return Settlement(
            countyId = before.countyId,
            ownerNationId = captorNationId,
            population = Math.addExact(before.population, before.garrisonTroops),
            garrisonTroops = 0,
            disarmedToCivilians = before.garrisonTroops,
        )
    }
}
