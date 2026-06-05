package opensamguk.gameapi.dto

/** 베팅 참여 응답 (개별 ng_betting 행). */
data class BettingItemResponse(
    val id: Int?,
    val bettingId: Int,
    val generalId: Int,
    val userId: Int?,
    val bettingType: String,
    val amount: Int,
)

/**
 * 베팅 상세 응답 (W3 enriched). PHP grand truth: `API/Betting/GetBettingDetail.php`.
 *
 * 담는 것:
 *  - [market]        = 베팅 마스터([BettingMarket]) — `game_kv`(table='betting') jsonb([BettingInfo]) 디코드.
 *                      마스터가 없으면 null(해당 베팅 없음).
 *  - [candidates]    = 후보 목록(index → 표시 정보). PHP `BettingInfo.candidates`.
 *  - [bettingDetail] = `[bettingType, sumAmount]` 튜플 배열. PHP `bettingDetail`(GROUP BY betting_type).
 *
 * **명시적 비포함 — `배당`(odds, §W3_PLAN §2 contract note):** PHP는 배당을 *서버에서 계산해
 * 반환하지 않는다*. 클라이언트가 `bettingDetail`/`myBetting`으로 직접 산출한다. 백엔드는 절대 배당을
 * 돌려주지 않는다(여기서도 필드 없음 — 의도된 누락이지 BLOCKED 데이터가 아님).
 */
data class BettingDetailResponse(
    val bettingId: Int,
    /** 베팅 마스터(game_kv 디코드). 없으면 null. */
    val market: BettingMarket?,
    /** 후보 index → 표시 정보. */
    val candidates: List<BettingCandidate>,
    /** `[bettingType, sumAmount]` 튜플 — betting_type별 누적 베팅액. */
    val bettingDetail: List<BettingDetailItem>,
)

/**
 * 베팅 마스터 와이어 — PHP `BettingInfo`의 표시 서브셋([opensamguk.logic.betting.BettingInfo] 동치).
 * candidates는 [BettingDetailResponse.candidates]로 별도 평탄화돼 여기엔 메타만 둔다.
 */
data class BettingMarket(
    val id: Int,
    val type: String,
    val name: String,
    val finished: Boolean,
    val selectCnt: Int,
    val isExclusive: Boolean?,
    val reqInheritancePoint: Boolean,
    val openYearMonth: Int,
    val closeYearMonth: Int,
)

/** 후보 한 항목 — PHP `SelectItem`(index 보존). */
data class BettingCandidate(
    /** 후보 인덱스(BettingInfo.candidates 의 key). */
    val index: Int,
    val title: String,
    val info: String?,
    /** 부가 데이터(nation 베팅의 경우 nation id 등). */
    val aux: Map<String, Any?>?,
)

/** `bettingDetail` 한 항목 — `[bettingType, sumAmount]`. */
data class BettingDetailItem(
    val bettingType: String,
    val sumAmount: Long,
)
