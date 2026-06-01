package opensamguk.logic.betting

import kotlinx.serialization.Serializable

/**
 * 베팅 타입 — PHP `BettingType` enum의 Kotlin 포팅.
 */
enum class BettingType {
    /** 국가 강약 베팅 */
    NATION_STRENGTH,
    /** 토너먼트 베팅 */
    TOURNAMENT,
    /** 커스텀 베팅 */
    CUSTOM,
}

/**
 * 베팅 상태 — PHP `BettingStatus` enum의 Kotlin 포팅.
 */
enum class BettingStatus {
    /** 베팅 개시 중 */
    OPEN,
    /** 베팅 마감 (보상 분배 전) */
    CLOSED,
    /** 보상 분배 완료 */
    PAYOUT_DONE,
}

/**
 * 베팅 마감 조건 — PHP `Betting::$closeCondition`의 Kotlin 포팅.
 */
@Serializable
sealed class CloseCondition {
    /**
     * 남은 국가 수 기준 마감.
     *
     * @property count 목표 남은 국가 수 (1 = 1국만 남을 때 마감)
     */
    @Serializable
    data class RemainNationCount(val count: Int) : CloseCondition()

    /**
     * 특정 연/월 기준 마감.
     *
     * @property year 목표 연도
     * @property month 목표 월
     */
    @Serializable
    data class SpecificDate(val year: Int, val month: Int) : CloseCondition()

    /**
     * 특정 국가 멸망 기준 마감.
     *
     * @property targetNationId 멸망을 기다릴 대상 국가 ID
     */
    @Serializable
    data class TargetDestroyed(val targetNationId: Int) : CloseCondition()
}

/**
 * 베팅 정보 — KV 스토리지에 JSON으로 저장되는 베팅 마스터 데이터.
 *
 * PHP `Betting` 클래스의 상태 부분 Kotlin 포팅.
 * Key: `betting:{bettingId}` (KV Storage)
 *
 * @property bettingId 베팅 고유 ID
 * @property type 베팅 타입
 * @property openYearMonth 베팅 개시 연월
 * @property targetNations 대상 국가 ID 목록
 * @property odds 국가별 배당률 (nationId → odds)
 * @property closeCondition 마감 조건
 * @property isExclusivePayout 독점 보상 여부 (true = 1명이 전부, false = N등분)
 * @property totalPool 총 베팅액 누적
 * @property status 베팅 상태
 */
@Serializable
data class BettingInfo(
    val bettingId: String,
    val type: BettingType,
    val openYearMonth: YearMonth,
    val targetNations: List<Int>,
    val odds: Map<Int, Double>,
    val closeCondition: CloseCondition,
    val isExclusivePayout: Boolean = false,
    val totalPool: Int = 0,
    val status: BettingStatus = BettingStatus.OPEN,
) {
    companion object {
        /** KV 스토리지에서 사용하는 키 접두사 */
        const val KV_KEY_PREFIX = "betting:"
    }
}

/**
 * 연/월 데이터 클래스 — [BettingInfo]에서 사용.
 *
 * @property year 연도
 * @property month 월 (1-12)
 */
@Serializable
data class YearMonth(val year: Int, val month: Int) {
    override fun toString(): String = "${year}년 ${month}월"
}

/**
 * 베팅 결과 — [FinishNationBettingAction]의 반환 타입.
 */
sealed class BettingResult {
    /** 아직 마감 조건 미달 */
    data class NotYet(val remainingNations: Int) : BettingResult()
    /** 베팅 정보를 찾을 수 없음 */
    data object NotFound : BettingResult()
    /** 보상 분배 완료 */
    data class PayoutDone(val winnerCount: Int, val totalPayout: Int) : BettingResult()
}
