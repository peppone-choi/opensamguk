package opensamguk.gameapi.controller

import opensamguk.gameapi.read.WorldLogReadRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 전황(제 전황) read endpoint — `GET /api/world-log`. READ-only, PUBLIC(입구 로비/로그인 노출).
 *
 * 게이트웨이 route handler `/api/server-log/[id]`가 해당 서버 game-api의 이 엔드포인트로 서버사이드
 * 프록시(맵 프리뷰와 동일 패턴). 월드 전체 글로벌 이력(`log_entry` SYSTEM 스코프 history/summary)을
 * 최신순 30건 반환. 신선 시드면 빈 목록(절대 500). 게임 진행에 따라 정복/멸망/건국/작위 등이 누적된다.
 *
 * `text`는 패러티 로그 원문(devsam 색/태그 마크업 포함) 그대로 — 표시 가공(태그 제거/색 렌더)은 프론트.
 */
@RestController
@RequestMapping("/api/world-log")
class WorldLogController(
    private val worldLog: WorldLogReadRepository,
) {

    @GetMapping
    fun worldLog(): WorldLogResponse {
        val entries = worldLog.findRecentWorldLog(RECENT_LIMIT).map { row ->
            WorldLogEntry(id = row.id, year = row.year, month = row.month, text = row.text)
        }
        return WorldLogResponse(entries = entries)
    }

    companion object {
        private const val RECENT_LIMIT = 30
    }
}

data class WorldLogEntry(
    val id: Int,
    val year: Int,
    val month: Int,
    val text: String,
)

data class WorldLogResponse(
    val entries: List<WorldLogEntry>,
)
