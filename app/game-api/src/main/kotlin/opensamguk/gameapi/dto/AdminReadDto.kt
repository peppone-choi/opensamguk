package opensamguk.gameapi.dto

data class AdminGameSettingsResponse(
    val msg: String,
    val logWritable: Boolean,
    val scenarioCode: String?,
    val year: Int?,
    val month: Int?,
    val starttime: String?,
    val startyear: Int?,
    val maxgeneral: Int?,
    val maxnation: Int?,
    val turntime: String?,
    val turnterm: Int?,
    val turnOptions: List<Int>,
    val blockedWrites: List<AdminBlockedWrite>,
)

data class AdminBlockedWrite(
    val label: String,
    val reason: String,
)

data class AdminGeneralModerationResponse(
    val generals: List<AdminGeneralModerationRow>,
    val bulkActions: List<AdminBlockedWrite>,
    val selectedActions: List<AdminBlockedWrite>,
)

data class AdminGeneralModerationRow(
    val no: Int,
    val name: String,
    val npc: Int,
    val block: Int,
    val killturn: Int?,
    val nationId: Int,
    val turnTime: String?,
    val command0: String?,
    val command1: String?,
)

/**
 * B3a/B4a/B4b — 어드민 read API(`_admin5`/`_admin7`/`_admin8`) 응답 DTO 모음.
 *
 * 전부 READ-only(game-api JPA read repo만 소비, mutation 없음). 0.9.0 단일 ADMIN 롤 게이트
 * (legacy `userGrade < 6` 거부의 0.9.0 등가 — grade 다단계는 의도적 divergence).
 * legacy 정렬키/라벨은 verbatim, 값은 PHP 집계(ROUND/AVG/SUM)를 충실 재현한다.
 */

// ──────────────────────────────────────────────────────────────────────────
// B3a — 일제정보(`_admin5.php`) 국가별 전체 통계
// ──────────────────────────────────────────────────────────────────────────

/**
 * `_admin5.php:50-83`의 정렬 select 옵션(verbatim). type 0~17(2/11/12 결번은 legacy도 미사용),
 * type2 0~6. FE 정렬 select가 그대로 렌더한다(라벨 = PHP option 텍스트 byte-동일).
 */
data class AdminNationStatsSortOption(
    val value: Int,
    val label: String,
)

/**
 * `_admin5.php:135-260`의 국가별 통계 1행. PHP는 nation×general JOIN + 별도 general/city 집계 쿼리로
 * 산출한다(컬럼 라벨 = 테이블 헤더 verbatim).
 *  - power/tech/gold/rice/strategicCmdLimit : nation 컬럼/메타.
 *  - genCnt/avgGold/avgRice/avgLeadership/avgStrength/avgIntel/avgExpLevel/sumLeadership/sumCrew : general 집계.
 *  - dex1~5 : 국가 소속 장수의 보·궁·기·귀·차 숙련 평균(ROUND(AVG(dex)) — general.meta 라이드).
 *  - cityCnt/pop/popMax/popRate/agri/comm/secu/wall/def : city 집계(%).
 *
 * tech는 PHP `sprintf('%.1f', tech)`(1자리), avg* 능력치는 `ROUND(AVG(...),1)`(1자리), avgGold/avgRice/
 * dex/agri 등은 `ROUND(...)`/`ROUND(...,2)`. 본 DTO는 PHP와 동일 자릿수의 수치를 그대로 내려보내고
 * 추가 표기 가공(천단위 콤마 등)은 FE 소관.
 */
data class AdminNationStatsRow(
    val nationId: Int,
    val name: String,
    val color: String,
    val power: Int,
    val genCnt: Int,
    val cityCnt: Int,
    val tech: Double,
    val strategicCmdLimit: Int,
    val gold: Int,
    val rice: Int,
    val avgGold: Long,
    val avgRice: Long,
    val avgLeadership: Double,
    val avgStrength: Double,
    val avgIntel: Double,
    val avgExpLevel: Double,
    val dex1: Long,
    val dex2: Long,
    val dex3: Long,
    val dex4: Long,
    val dex5: Long,
    val sumCrew: Long,
    val sumLeadership: Long,
    val pop: Long,
    val popMax: Long,
    val popRate: Double,
    val agri: Double,
    val comm: Double,
    val secu: Double,
    val wall: Double,
    val def: Double,
)

/**
 * B3a 응답 봉투. type/type2는 echo된 현재 정렬 상태(legacy `$sel[$type]='selected'`의 등가),
 * sortOptions/sortOptions2는 select 옵션 리스트(verbatim), rows는 정렬된 국가 통계.
 *
 * historyStats/sabotageLog는 legacy `_admin5`에 존재하나 opensamguk 스키마에 원천이 없어 BLOCKED:
 *  - historyStats : `statistic` 테이블 미존재(월별 누적 통계 스냅샷) → 빈 리스트 + blocked 플래그.
 *  - sabotageLog  : `getSabotageLogRecent`는 플랫파일(`_sabotagelog.txt`) 기반, DB 원천 없음 → 빈 리스트.
 * 값 날조 금지(CLAUDE.md §5) — 누락은 quarantine으로 표기만 한다.
 */
data class AdminNationStatsResponse(
    val type: Int,
    val type2: Int,
    val sortOptions: List<AdminNationStatsSortOption>,
    val sortOptions2: List<AdminNationStatsSortOption>,
    val rows: List<AdminNationStatsRow>,
    val historyStats: List<Any> = emptyList(),
    val historyStatsBlocked: Boolean = true,
    val sabotageLog: List<String> = emptyList(),
    val sabotageLogBlocked: Boolean = true,
)

// ──────────────────────────────────────────────────────────────────────────
// B4a — 로그정보(`_admin7.php`) 장수 상세/기록
// ──────────────────────────────────────────────────────────────────────────

/**
 * `_admin7.php:15-31`의 queryMap 정렬 옵션(verbatim 4종). queryType = 키(turntime/recent_war/name/warnum),
 * label = `$queryTypeText`(최근턴/최근전투/장수명/전투수).
 */
data class AdminGeneralSortOption(
    val queryType: String,
    val label: String,
)

/**
 * `_admin7.php:113-114`의 대상장수 select 1행. `name (turntime 14,5)` — turntime 문자열의 14번째부터
 * 5자(=HH:MM 분초)를 라벨 보조로 노출한다. opensamguk turn_time(timestamptz)에선 "HH:MM"에 해당.
 */
data class AdminGeneralSelectOption(
    val no: Int,
    val name: String,
    val turnTimeHm: String,
)

/**
 * `_admin7.php:133-168`의 장수 정보 + 4개 로그 패널. PHP `general_record`의 log_type별 분기를
 * opensamguk `log_entry`(scope=GENERAL) category로 매핑:
 *  - actionLog(개인 기록)  ← getGeneralActionLogRecent(gen,24)  = category ACTION
 *  - battleDetailLog(전투 기록) ← getBattleDetailLogRecent(gen,24) = category BATTLE_DETAIL
 *  - historyLog(장수 열전) ← getGeneralHistoryLogAll(gen)       = category HISTORY (전체)
 *  - battleResultLog(전투 결과) ← getBattleResultRecent(gen,24)  = category BATTLE_BRIEF
 * 각 로그는 newest-first(PHP `order by id desc`). text는 패러티 로그 원문(색/태그 마크업 포함) 그대로.
 *
 * generalInfo/generalInfo2(`_admin7.php:133-134`)는 General::createObjFromDB(FullWithAccessLog)의
 * 풍부한 HTML 렌더로, 본 read API는 핵심 스칼라(이름/국가/능력치/관직/npc/turnTime)만 평면 노출한다.
 * 전체 generalInfo HTML 패러티(접속로그/장비/특기 마크업)는 FE 렌더 책임 + 별도 상세 API 백로그.
 */
data class AdminGeneralDetail(
    val no: Int,
    val name: String,
    val nationId: Int,
    val npc: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val politics: Int, // 정치/매력 (RTK14 divergence)
    val charm: Int, // 정치/매력 (RTK14 divergence)
    val officerLevel: Int,
    val turnTime: String?,
    val actionLog: List<String>,
    val battleDetailLog: List<String>,
    val historyLog: List<String>,
    val battleResultLog: List<String>,
)

/**
 * B4a 응답 봉투. queryType = 현재 정렬 키, sortOptions = 정렬 select(verbatim),
 * generalList = 정렬된 대상장수 select 목록, gen = 선택된 장수 no, detail = 그 장수 상세(없으면 null).
 */
data class AdminGeneralLogResponse(
    val queryType: String,
    val sortOptions: List<AdminGeneralSortOption>,
    val generalList: List<AdminGeneralSelectOption>,
    val gen: Int,
    val detail: AdminGeneralDetail?,
)

// ──────────────────────────────────────────────────────────────────────────
// B4b — 외교정보(`_admin8.php`) 전 국가간 외교 (NO masking)
// ──────────────────────────────────────────────────────────────────────────

/**
 * `_admin8.php:78-111`의 외교 관계 1행. `me < you AND state != 2(통상)` 필터 + `order by state desc`.
 * stateText는 PHP switch verbatim(교 전/선포중/통 상/불가침). 어드민이므로 마스킹 없음
 * (`GetDiplomacy.php`의 중립 마스킹과 대비 — 모든 state 원본 노출).
 */
data class AdminDiplomacyRow(
    val me: Int,
    val meName: String,
    val meColor: String,
    val you: Int,
    val youName: String,
    val youColor: String,
    val state: Int,
    val stateText: String,
    val term: Int,
)

/** B4b 응답 봉투 — 전 국가간 외교 행 리스트(`me < you`, state desc 정렬, state==2 제외). */
data class AdminDiplomacyAllResponse(
    val relations: List<AdminDiplomacyRow>,
)
