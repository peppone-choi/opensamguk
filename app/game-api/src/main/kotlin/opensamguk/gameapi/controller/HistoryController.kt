package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.HistoryRecord
import opensamguk.gameapi.dto.HistoryResponse
import opensamguk.gameapi.read.HistoryReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/history?yearMonth=` (연감, spec page 16). READ-only, PUBLIC.
 *
 * 와이어 정합(PageHistory.vue + Global/GetHistory.php): 응답은 셀렉터 정적값(firstYearMonth/lastYearMonth/
 * currentYearMonth/serverId/mapName)과 선택 월 레코드(`record`)를 담는다. FE는 `record`로 MapViewer(map)/
 * SimpleNationList(nations)/중원정세(globalHistory)/장수동향(globalAction)을 렌더한다.
 *
 * Backed by `yearbook_history`(월별 map/nations 스냅샷의 충실 포팅 — 이 스키마엔 `ng_history` 테이블이
 * 없다). 테이블은 존재하나 fresh seed에선 0행(월틱이 진행하며 매월 1행 기록) → `record=null` + 범위 0,
 * 절대 500/날조 없음. 교차 서버 뷰는 F4(단일서버)에서 드롭.
 *
 * **BLOCKED(§5)**: `yearbook_history`엔 `global_history`/`global_action` 컬럼이 없어(중원 정세/장수 동향
 * 월별 글로벌 로그) `record.globalHistory`/`globalAction`은 항상 빈 배열로 나간다. 월별 글로벌 로그가
 * 영속화되면 1줄로 채워진다(REPORT 기재).
 *
 * `yearMonth`(선택) = `Util::joinYearMonth` = `year*12 + (month-1)`. 부재 시 currentYearMonth(=last+1)로
 * 클램프, 행이 없으면 마지막 행을 선택. parseYearMonth = [ym/12, ym%12+1]로 FE와 동형.
 */
@RestController
@RequestMapping("/api/history")
class HistoryController(
    private val history: HistoryReadRepository,
    private val world: WorldStateReadRepository,
) {

    @GetMapping
    fun history(@RequestParam(name = "yearMonth", required = false) yearMonth: Int?): ResponseEntity<HistoryResponse> {
        val rows = history.findAllByOrderByYearAscMonthAsc()
        val serverId = world.findAll().firstOrNull()?.scenarioCode ?: ""
        // mapName: opensamguk엔 별도 맵 테마명이 없다(시나리오 코드로 대체). MapViewer는 record.map으로 렌더.
        val mapName = serverId

        // 행이 없으면 빈 상태(셀렉터 범위 0, record null). PHP는 빈 ng_history에서 에러 반환하지만, read-only
        // 빈 연감은 200 + 빈 셰이프가 옳다(F4 단일서버, 날조 없음).
        if (rows.isEmpty()) {
            return ResponseEntity.ok(
                HistoryResponse(
                    result = true,
                    firstYearMonth = 0,
                    lastYearMonth = 0,
                    currentYearMonth = 0,
                    serverId = serverId,
                    mapName = mapName,
                    record = null,
                ),
            )
        }

        val firstRow = rows.first()
        val lastRow = rows.last()
        val firstYearMonth = joinYearMonth(firstRow.year, firstRow.month)
        val lastYearMonth = joinYearMonth(lastRow.year, lastRow.month)
        // 진행중 서버: 현재 달은 마지막 기록의 다음 달(PageHistory.vue currentYearMonth = last+1).
        val currentYearMonth = lastYearMonth + 1

        // 선택 월: yearMonth 미지정/범위 밖이면 마지막 기록 월로 클램프(빈 상태 회피).
        val targetYearMonth = (yearMonth ?: lastYearMonth).coerceIn(firstYearMonth, lastYearMonth)
        val (tYear, tMonth) = parseYearMonth(targetYearMonth)
        val selected = rows.firstOrNull { it.year == tYear && it.month == tMonth }

        val record = selected?.let { h ->
            HistoryRecord(
                serverId = serverId,
                year = h.year,
                month = h.month,
                // BLOCKED: yearbook_history 미보유(§5) — 월별 글로벌 로그 컬럼 부재 → 빈 배열.
                globalHistory = emptyList(),
                globalAction = emptyList(),
                nations = h.nations,
                map = h.map,
                hash = h.hash,
            )
        }

        return ResponseEntity.ok(
            HistoryResponse(
                result = true,
                firstYearMonth = firstYearMonth,
                lastYearMonth = lastYearMonth,
                currentYearMonth = currentYearMonth,
                serverId = serverId,
                mapName = mapName,
                record = record,
            ),
        )
    }

    /** PHP `Util::joinYearMonth($year, $month)` = `$year * 12 + ($month - 1)`. */
    private fun joinYearMonth(year: Int, month: Int): Int = year * 12 + (month - 1)

    /** PHP `parseYearMonth`(util/parseYearMonth.ts) = `[ym/12, ym%12 + 1]`(정수 나눗셈). */
    private fun parseYearMonth(yearMonth: Int): Pair<Int, Int> = Pair(yearMonth / 12, yearMonth % 12 + 1)
}
