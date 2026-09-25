package opensamguk.engine.hwiha

/**
 * 전쟁 결과 → 명망 사건 기록의 경계. 조우 정산과 縣 함락이 이 두 메서드만 부른다.
 *
 * 기록 규칙(전공·패전·縣 점령/상실, 월 1회 등)의 정본 구현은 기록 스트림(`HwihaRenownEventRecorder`)에 있고
 * 병합 때 여기에 연결한다. 이 브랜치의 기본값은 [NONE](무동작)이다 — 두 기록기를 함께 켜서 같은 사건을 두 번
 * 세지 않게 한다.
 *
 * - [onEncounterResolved]: 조우 전투가 끝난 뒤 **한 번**. 승자가 없는 결말(양쪽 모두 퇴각·궤멸)은 부르지 않는다.
 * - [onCountyCaptured]: 縣 소유가 넘어간 **직후 한 번**.
 */
interface HwihaWarOutcomeListener {
    fun onEncounterResolved(winnerIds: List<Int>, loserIds: List<Int>) {}
    fun onCountyCaptured(countyId: Int, previousNationId: Int, captorNationId: Int, capturerIds: List<Int>) {}

    companion object {
        val NONE: HwihaWarOutcomeListener = object : HwihaWarOutcomeListener {}
    }
}
