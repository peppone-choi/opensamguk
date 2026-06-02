package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.HistoryMonth
import opensamguk.gameapi.dto.HistoryResponse
import opensamguk.gameapi.read.HistoryReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/history?yearMonth=` (연감, spec page 16). READ-only, PUBLIC.
 *
 * Backed by `yearbook_history` (the faithful port of the per-month yearbook snapshots — there is NO
 * `ng_history` table in this schema). The table EXISTS but carries ZERO rows in the fresh seed (the
 * monthly pipeline writes a row each month as the game advances) → empty `months`, never 500, no
 * fabrication. Cross-server view is dropped for F4 (single-server) per the spec.
 *
 * `yearMonth` (optional) is the legacy joined `year*100 + month` filter (e.g. 18103 = 181년 3월). When
 * present, only that month's snapshot is returned; otherwise the full chronological range.
 */
@RestController
@RequestMapping("/api/history")
class HistoryController(
    private val history: HistoryReadRepository,
) {

    @GetMapping
    fun history(@RequestParam(name = "yearMonth", required = false) yearMonth: Int?): ResponseEntity<HistoryResponse> {
        val rows = if (yearMonth != null) {
            val year = yearMonth / 100
            val month = yearMonth % 100
            history.findByYearAndMonthOrderByIdAsc(year, month)
        } else {
            history.findAllByOrderByYearAscMonthAsc()
        }
        val months = rows.map { h ->
            HistoryMonth(
                year = h.year,
                month = h.month,
                profileName = h.profileName,
                map = h.map,
                nations = h.nations,
            )
        }
        return ResponseEntity.ok(HistoryResponse(result = true, months = months))
    }
}
