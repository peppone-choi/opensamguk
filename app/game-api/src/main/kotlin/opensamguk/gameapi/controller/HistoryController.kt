package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.HistoryRecord
import opensamguk.gameapi.dto.HistoryResponse
import opensamguk.gameapi.read.HistoryReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
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
 * Backed by `yearbook_history`(월별 map/nations/global_history/global_action 스냅샷의 충실 포팅).
 * 테이블은 존재하나 fresh seed에선 0행(월틱이 진행하며 매월 1행 기록). 레거시 현재 서버는 빈
 * `ng_history`에서도 `world_state` 현재 월 기준으로 이전 월 범위 + 현재 월 옵션을 만든다.
 * 교차 서버 뷰는 F4(단일서버)에서 드롭.
 *
 * `yearMonth`(선택) = `Util::joinYearMonth` = `year*12 + (month-1)`. 행이 없으면 `record=null`이며,
 * 기록되지 않은 현재 월에 직전 archive를 재라벨하지 않는다. parseYearMonth = [ym/12, ym%12+1]로 FE와 동형.
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
        val currentWorld = world.findProcessWorld()
        val serverId = currentWorld?.scenarioCode ?: ""
        // mapName: opensamguk엔 별도 맵 테마명이 없다(시나리오 코드로 대체). MapViewer는 record.map으로 렌더.
        val mapName = serverId
        val liveYearMonth = currentYearMonth(currentWorld)

        // 현재 서버의 빈 연감은 PHP `v_history.php`처럼 현재 월의 직전 월을 first/last로 삼고 현재 월 옵션을
        // 노출한다. world_state까지 없는 진짜 빈 DB만 0-range로 둔다.
        if (rows.isEmpty()) {
            val lastYearMonth = liveYearMonth?.let { it - 1 } ?: 0
            return ResponseEntity.ok(
                HistoryResponse(
                    result = true,
                    firstYearMonth = lastYearMonth,
                    lastYearMonth = lastYearMonth,
                    currentYearMonth = liveYearMonth ?: 0,
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
        // 진행중 서버: PHP staticValues.currentYearMonth는 gameStor 현재 월이다. 월 기록이 더 오래된
        // 테스트/덤프에서는 기존 last+1 폴백을 보존한다.
        val currentYearMonth = liveYearMonth ?: (lastYearMonth + 1)

        val selectionUpper = maxOf(lastYearMonth, currentYearMonth)
        val targetYearMonth = (yearMonth ?: currentYearMonth).coerceIn(firstYearMonth, selectionUpper)
        val (tYear, tMonth) = parseYearMonth(targetYearMonth)
        val selected = rows.firstOrNull { it.year == tYear && it.month == tMonth }

        val record = selected?.let { h ->
            HistoryRecord(
                serverId = serverId,
                year = h.year,
                month = h.month,
                globalHistory = h.globalHistory,
                globalAction = h.globalAction,
                nations = h.nations.value,
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

    private fun currentYearMonth(world: WorldStateReadEntity?): Int? =
        world
            ?.takeIf { it.currentYear > 0 && it.currentMonth in 1..12 }
            ?.let { joinYearMonth(it.currentYear, it.currentMonth) }
}
