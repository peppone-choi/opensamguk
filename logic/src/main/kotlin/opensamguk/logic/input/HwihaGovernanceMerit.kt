package opensamguk.logic.input

/**
 * 치적 사건: 縣令으로 배치된 카드가 앉은 縣의 지표가 지난달보다 올랐다(§8.2 「치적은 명망으로 이어진다」).
 * [ownerGeneralId] 는 그 카드를 거느린 장수(치적을 받을 쪽), [risen] 은 오른 지표 이름(고정 순서:
 * population, agriculture, commerce, security, trust, defence, wall), [monthStamp] 은 비교한 달 "YYYY-MM".
 * 치적이 명망으로 얼마나 이어지는지는 기록 스트림이 정한다 — 여기서는 사건만 낸다.
 */
data class HwihaGovernanceMeritEvent(
    val ownerGeneralId: Int,
    val cardGeneralId: Int,
    val retainerId: Int,
    val countyId: Int,
    val monthStamp: String,
    val previous: HwihaCountyIndicators,
    val current: HwihaCountyIndicators,
    val risen: List<String>,
) {
    init { require(ownerGeneralId > 0 && cardGeneralId > 0 && retainerId > 0 && countyId > 0 && risen.isNotEmpty()) }
}

/**
 * 치적 사건을 받는 자리(기본은 버림). 기록 스트림의 `HwihaRenownEvents.recordRenownEvent(meta, kind, stamp, source?)` 에는
 * 병합 때 이 인터페이스의 구현으로 잇는다 — 이 스트림은 그 함수를 직접 부르지 않는다.
 * 엔진은 월 경계(상순의 순 경계 3단계 뒤)에서 縣 id 순으로 한 번씩 부른다. 구현은 ChangeRecorder 경로로만 써야 한다.
 */
fun interface HwihaGovernanceMeritSink {
    fun onCountyIndicatorsRose(event: HwihaGovernanceMeritEvent)

    companion object {
        /** 기록 스트림이 연결되기 전의 기본값: 사건을 버린다(명망 변화 없음). */
        val NONE: HwihaGovernanceMeritSink = HwihaGovernanceMeritSink { }
    }
}
