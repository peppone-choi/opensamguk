package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty

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
 * D5 — 베팅 상세 응답. PHP grand truth: `API/Betting/GetBettingDetail.php:86-94`.
 *
 * PHP 봉투는 **정확히 7키**: `{result, bettingInfo, bettingDetail, myBetting, remainPoint, year, month}`.
 *  - [result]        = 항상 true(마스터 부재 시 PHP는 본문 대신 '해당 베팅이 없습니다' 문자열 반환).
 *  - [bettingInfo]   = **raw `$rawBettingInfo`**(BettingInfo 배열 그대로) — candidates 인라인 + winner 포함.
 *                      각 candidate(SelectItem)에는 `isHtml`/`title`/`info`/`aux`가 그대로 들어간다.
 *                      PHP는 가공 없이 통째로 날린다(jsonb 디코드 → 삽입순서 보존 Map).
 *  - [bettingDetail] = `[bettingType, sumAmount]` 튜플 배열. PHP `bettingDetail`(GROUP BY betting_type).
 *  - [myBetting]     = 로그인 사용자의 `[bettingType, sumAmount]` 튜플 배열(GROUP BY, user_id 필터).
 *  - [remainPoint]   = reqInheritancePoint ? `inheritance_{userID}.previous[0]` : `general.gold`(부재 0).
 *  - [year]/[month]  = world_state current.
 *
 * **명시적 비포함:**
 *  - `배당`(odds): PHP는 배당을 *서버에서 계산해 반환하지 않는다*. FE가 bettingDetail/myBetting으로 직접 산출.
 *  - `userCnt`: PHP D5 봉투에 없다(D10 Vote 소유). 이전 구현이 잘못 추가했던 것 → 제거.
 *  - `market`/`candidates` 분리: PHP는 raw `bettingInfo` 한 키로 통합 — 분리하지 않는다.
 */
data class BettingDetailResponse(
    val result: Boolean,
    /** raw BettingInfo 배열(candidates 인라인 + winner). PHP `$rawBettingInfo` 통째 패스스루. */
    val bettingInfo: Map<String, Any?>,
    /** `[bettingType, sumAmount]` 튜플 — betting_type별 누적 베팅액. */
    val bettingDetail: List<BettingDetailItem>,
    /** 내 베팅(betting_type별) — 로그인 사용자 기준. */
    val myBetting: List<BettingDetailItem>,
    /** 남은 포인트(reqInheritancePoint ? 유산포인트 : 금). */
    val remainPoint: Int,
    /** 현재 게임 연도. */
    val year: Int,
    /** 현재 게임 월. */
    val month: Int,
)

/** `bettingDetail` 한 항목 — `[bettingType, sumAmount]`. */
data class BettingDetailItem(
    val bettingType: String,
    val sumAmount: Long,
)

/**
 * D4 — 베팅 전체 목록 응답. PHP grand truth: `GetBettingList.php:67-71/86-90`.
 *
 * 봉투(정확히 4키): `{result:true, bettingList(Map<id,item>), year, month}`.
 */
data class BettingListResponse(
    /** 항상 true(PHP는 본문/예외 모두 result:true 동봉). */
    val result: Boolean,
    /** Map<bettingId, BettingListItem> — PHP가 Map으로 날림. */
    val bettingList: Map<Int, BettingListItem>,
    val year: Int,
    val month: Int,
)

/**
 * D4 — 베팅 목록 한 항목. PHP `GetBettingList.php`가 날리는 필드 서브셋.
 *
 * candidates는 legacy unset(제거).
 */
data class BettingListItem(
    val id: Int,
    val type: String,
    val name: String,
    val finished: Boolean,
    val selectCnt: Int,
    // PHP/FE `isExclusive` 와이어 이름 고정 (Jackson `is` 접두 제거 회피).
    @get:JsonProperty("isExclusive")
    val isExclusive: Boolean?,
    val reqInheritancePoint: Boolean,
    val openYearMonth: Int,
    val closeYearMonth: Int,
    /** 당첨 후보 인덱스 목록 (보상 분배 후 설정). */
    val winner: List<Int>?,
    /** 해당 베팅의 전체 누적 베팅액(SUM amount). */
    val totalAmount: Long,
)

